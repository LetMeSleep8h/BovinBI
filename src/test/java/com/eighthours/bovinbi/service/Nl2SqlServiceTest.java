package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.agent.AgentOrchestrator;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.ColInfo;
import com.eighthours.bovinbi.dto.ExecResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 查询管线编排单测(Mockito 隔离 Schema 召回/LLM/执行器,其余组件用真实实现):
 * 固化"双引擎 + 自修复 + 规则兜底"降级链契约 —— LLM 任何一环失败,最终都必须
 * 要么给出规则引擎结果,要么优雅降级,绝不让异常冒泡成"查询失败"。
 */
class Nl2SqlServiceTest {

    private static final Long DS = 1L;

    private SchemaRetriever schemaRetriever;
    private LlmSqlGenerator llmSqlGenerator;
    private QueryExecutor queryExecutor;
    @SuppressWarnings("unchecked")
    private final ObjectProvider<AgentOrchestrator> agentProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.eighthours.bovinbi.service.MultiAgentService> multiAgentProvider = mock(ObjectProvider.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<com.eighthours.bovinbi.service.rag.EmbeddingClient> embedderProvider = mock(ObjectProvider.class);
    private BovinProperties props;
    private Nl2SqlService service;

    @BeforeEach
    void setUp() {
        schemaRetriever = mock(SchemaRetriever.class);
        llmSqlGenerator = mock(LlmSqlGenerator.class);
        queryExecutor = mock(QueryExecutor.class);
        props = new BovinProperties();
        when(schemaRetriever.retrieve(anyLong(), anyString())).thenReturn(new SchemaRetriever.LinkedSchema(
                "【数据集】牧场养殖分析",
                Set.of("dwh_fact_milk", "dwh_dim_cattle", "dwh_dim_farm")));
        when(queryExecutor.execute(anyString())).thenReturn(oneRow());
        service = new Nl2SqlService(
                new TimeRangeParser(LocalDate.of(2026, 9, 5)),
                new ChitChatHandler(),
                schemaRetriever,
                new SemanticCache(props),
                new RuleSqlGenerator(),
                llmSqlGenerator,
                new SqlGuard(props),
                queryExecutor,
                new ChartAdvisor(),
                props,
                agentProvider,
                multiAgentProvider,
                embedderProvider);
    }

    private static ExecResult oneRow() {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>(Map.of("产奶量", 100.0));
        return new ExecResult(List.of(new ColInfo("产奶量", "DECIMAL")),
                new ArrayList<>(List.of(row)), 1, 5);
    }

    private static final String GOOD_SQL =
            "SELECT ROUND(SUM(m.milk_yield), 2) AS 产奶量 FROM dwh_fact_milk m";
    private static final String BAD_SQL = "SELECT * FROM secret_table";

    @Test
    void chitChatShortCircuits() {
        AnswerPayload p = service.answer(DS, "你好");
        assertFalse(p.isFallback());
        assertEquals("RULE", p.getEngine());
        verifyNoInteractions(schemaRetriever, queryExecutor, llmSqlGenerator);
    }

    @Test
    void cacheHitReturnsImmediately() {
        service.answer(DS, "今年总产奶量");
        AnswerPayload hit = service.answer(DS, "今年总产奶量!");
        assertTrue(hit.isCacheHit());
        assertEquals("CACHE", hit.getEngine());
    }

    @Test
    void ruleModeWhenProviderMock() {
        AnswerPayload p = service.answer(DS, "今年总产奶量");
        assertEquals("RULE", p.getEngine());
        assertTrue(p.getSql().toLowerCase().contains("sum(m.milk_yield)"));
        verifyNoInteractions(llmSqlGenerator);
    }

    @Test
    void llmHappyPath() {
        props.getLlm().setProvider("openai");
        when(llmSqlGenerator.generate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SqlResult(GOOD_SQL, "今年产奶量合计"));

        AnswerPayload first = service.answer(DS, "今年总产奶量");
        assertEquals("LLM", first.getEngine());
        // 载荷里应是守护后的最终 SQL(强制追加 LIMIT)
        assertTrue(first.getSql().startsWith(GOOD_SQL));
        assertTrue(first.getSql().toLowerCase().endsWith("limit 1000"));

        AnswerPayload second = service.answer(DS, "今年总产奶量");
        assertEquals("CACHE", second.getEngine());
    }

    @Test
    void llmGenerateFailsFallsBackToRule() {
        props.getLlm().setProvider("openai");
        when(llmSqlGenerator.generate(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BizException(502, "LLM 超时"));

        AnswerPayload p = service.answer(DS, "今年总产奶量");
        assertEquals("RULE(降级)", p.getEngine());
        assertFalse(p.isFallback());
        assertTrue(p.getSql().toLowerCase().contains("sum(m.milk_yield)"));
    }

    @Test
    void llmSqlRejectedRepairsOnce() {
        props.getLlm().setProvider("openai");
        when(llmSqlGenerator.generate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SqlResult(BAD_SQL, "引用了白名单外的表"));
        when(llmSqlGenerator.repair(org.mockito.ArgumentMatchers.any(), anyString(), anyString()))
                .thenReturn(new SqlResult(GOOD_SQL, "已修正"));

        AnswerPayload p = service.answer(DS, "今年总产奶量");
        assertEquals("LLM(修复)", p.getEngine());
        assertTrue(p.getSql().startsWith(GOOD_SQL));
        assertTrue(p.getSql().toLowerCase().endsWith("limit 1000"));
    }

    @Test
    void repairFailsStillFallsBackToRule() {
        props.getLlm().setProvider("openai");
        when(llmSqlGenerator.generate(org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SqlResult(BAD_SQL, "引用了白名单外的表"));
        when(llmSqlGenerator.repair(org.mockito.ArgumentMatchers.any(), anyString(), anyString()))
                .thenThrow(new BizException(502, "修复失败"));

        AnswerPayload p = service.answer(DS, "今年总产奶量");
        assertEquals("RULE(降级)", p.getEngine());
        assertFalse(p.isFallback());
        assertTrue(p.getSql().toLowerCase().contains("sum(m.milk_yield)"));
    }

    @Test
    void fallbackDisabledPropagates() {
        props.getLlm().setProvider("openai");
        props.getChat().setFallbackToRule(false);
        when(llmSqlGenerator.generate(org.mockito.ArgumentMatchers.any()))
                .thenThrow(new BizException(502, "LLM 不可用"));

        assertThrows(BizException.class, () -> service.answer(DS, "今年总产奶量"));
    }

    @Test
    void unanswerableQuestionDegradesGracefully() {
        AnswerPayload p = service.answer(DS, "灰犀牛和黑天鹅有什么区别");
        assertTrue(p.isFallback());
        assertNotNull(p.getFallbackHint());
        assertNull(p.getSql());
    }

    @Test
    void agentEngineWithoutOrchestratorFallsBackToPipeline() {
        // engine=agent 但 orchestrator 不可用(如装配失败):外壳记 warn 后回退固定管线,不中断服务
        props.getChat().setEngine("agent");
        when(agentProvider.getIfAvailable()).thenReturn(null);

        AnswerPayload p = service.answer(DS, "今年总产奶量");
        assertEquals("RULE", p.getEngine());
        assertFalse(p.isFallback());
        assertTrue(p.getSql().toLowerCase().contains("sum(m.milk_yield)"));
    }
}
