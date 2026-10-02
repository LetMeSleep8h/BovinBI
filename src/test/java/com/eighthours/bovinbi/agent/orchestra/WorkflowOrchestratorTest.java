package com.eighthours.bovinbi.agent.orchestra;

import com.eighthours.bovinbi.agent.orchestra.nodes.ExecutorNode;
import com.eighthours.bovinbi.agent.orchestra.nodes.GuardReviewNode;
import com.eighthours.bovinbi.agent.orchestra.nodes.InsightNode;
import com.eighthours.bovinbi.agent.orchestra.nodes.SchemaNode;
import com.eighthours.bovinbi.agent.orchestra.nodes.SemanticReviewNode;
import com.eighthours.bovinbi.agent.orchestra.nodes.SqlWriterNode;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.ColInfo;
import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.llm.LlmClient;
import com.eighthours.bovinbi.service.ChitChatHandler;
import com.eighthours.bovinbi.service.ChartAdvisor;
import com.eighthours.bovinbi.service.QueryExecutor;
import com.eighthours.bovinbi.service.SchemaRetriever;
import com.eighthours.bovinbi.service.SqlGuard;
import com.eighthours.bovinbi.service.TimeRangeParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 多 Agent 编排契约(单测:桩 LlmClient/执行器,守护与时间解析用真实实现):
 * 1) Supervisor 路由:闲聊不进工作流,直接确定性直答;
 * 2) 修复环:守护拒绝 → sql_writer 按拒绝原因定向改写 → 通过 → 执行,轨迹含修复轮;
 * 3) 语义审查 fail 同样驱动修复环;
 * 4) 修复预算耗尽 → 抛出(外壳降级规则引擎),绝不返回半成品;
 * 5) 语义审查关闭时整步跳过(llm-review=false 只跑确定性审查)。
 */
class WorkflowOrchestratorTest {

    private SchemaRetriever schemaRetriever;
    private LlmClient llmClient;
    private QueryExecutor queryExecutor;
    private BovinProperties props;
    private ObjectMapper objectMapper;
    private WorkflowOrchestrator orchestrator;

    private static final String GOOD_SQL =
            "SELECT SUM(m.milk_yield) AS 产奶量 FROM dwh_fact_milk m "
                    + "WHERE m.record_date >= '2026-01-01' AND m.record_date < '2027-01-01' LIMIT 1000";
    private static final String WRONG_TABLE_SQL = "SELECT * FROM secret_table LIMIT 10";

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        schemaRetriever = mock(SchemaRetriever.class);
        llmClient = mock(LlmClient.class);
        queryExecutor = mock(QueryExecutor.class);
        props = new BovinProperties();
        objectMapper = new ObjectMapper();

        when(schemaRetriever.retrieve(anyLong(), anyString())).thenReturn(new SchemaRetriever.LinkedSchema(
                "【数据集】牧场养殖分析", Set.of("dwh_fact_milk", "dwh_dim_cattle", "dwh_dim_farm")));
        when(queryExecutor.execute(anyString())).thenReturn(oneRow());

        ObjectProvider<com.eighthours.bovinbi.service.rag1.Rag1IntentService> noRag1 = mock(ObjectProvider.class);
        orchestrator = new WorkflowOrchestrator(noRag1, props,
                new TimeRangeParser(LocalDate.of(2026, 9, 5)), new ChitChatHandler(),
                new SchemaNode(schemaRetriever), new SqlWriterNode(llmClient),
                new GuardReviewNode(new SqlGuard(props)), new SemanticReviewNode(llmClient),
                new ExecutorNode(queryExecutor), new InsightNode(new ChartAdvisor()));
    }

    /** LLM 桩:按脚本顺序回放;extractJson 用真实 ObjectMapper 解析 */
    private void script(String... rawResponses) throws Exception {
        java.util.Queue<String> q = new java.util.ArrayDeque<>(List.of(rawResponses));
        when(llmClient.chat(anyString(), anyString())).thenAnswer(inv -> {
            String next = q.poll();
            if (next == null) throw new BizException(502, "脚本耗尽:LLM 被意外调用");
            return next;
        });
        when(llmClient.extractJson(anyString())).thenAnswer(inv ->
                objectMapper.readTree((String) inv.getArgument(0)));
    }

    private static String sqlPayload(String sql) {
        try {
            Map<String, String> m = new LinkedHashMap<>();
            m.put("sql", sql);
            m.put("explanation", "测试");
            return new ObjectMapper().writeValueAsString(m);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ExecResult oneRow() {
        LinkedHashMap<String, Object> row = new LinkedHashMap<>(Map.of("产奶量", 100.0));
        return new ExecResult(List.of(new ColInfo("产奶量", "DECIMAL")),
                new ArrayList<>(List.of(row)), 1, 5);
    }

    @Test
    void chitChatRouteSkipsWorkflowEntirely() throws Exception {
        AnswerPayload p = orchestrator.answer(1L, "你好", null);
        assertEquals("RULE", p.getEngine(), "闲聊应确定性直答,不进工作流");
    }

    @Test
    void happyPathRunsThroughWorkflowWithTrace() throws Exception {
        props.getChat().getOrchestra().setLlmReview(false);
        script(sqlPayload(GOOD_SQL), "{\"verdict\":\"pass\"}");

        AnswerPayload p = orchestrator.answer(1L, "今年总产奶量", null);

        assertEquals("ORCHESTRA", p.getEngine());
        assertNotNull(p.getChart(), "洞察节点应产出图表建议");
        assertTrue(p.getTrace().stream().anyMatch(t -> "schema".equals(t.tool())), "轨迹应含 schema 节点");
        assertTrue(p.getTrace().stream().anyMatch(t -> "sql_writer".equals(t.tool())), "轨迹应含 sql_writer 节点");
        assertTrue(p.getTrace().stream().anyMatch(t -> "reviewer(guard)".equals(t.tool())), "轨迹应含确定性审查节点");
        assertTrue(p.getTrace().stream().anyMatch(t -> "executor".equals(t.tool())), "轨迹应含执行节点");
        assertTrue(p.getTrace().stream().noneMatch(t -> t.tool().contains("semantic")), "llm-review=false 语义审查应跳过");
    }

    @Test
    void guardRejectionDrivesRepairLoop() throws Exception {
        props.getChat().getOrchestra().setLlmReview(false);
        script(sqlPayload(WRONG_TABLE_SQL), sqlPayload(GOOD_SQL));

        AnswerPayload p = orchestrator.answer(1L, "今年总产奶量", null);

        assertEquals("ORCHESTRA", p.getEngine());
        assertTrue(p.getTrace().stream().anyMatch(t -> "sql_writer(修复)".equals(t.tool())),
                "轨迹应含修复轮的 sql_writer");
        assertTrue(p.getTrace().stream().anyMatch(t -> "reviewer(guard)".equals(t.tool()) && !t.ok()),
                "轨迹应含守护拒绝记录");
    }

    @Test
    void semanticReviewFailAlsoDrivesRepairLoop() throws Exception {
        props.getChat().getOrchestra().setMaxRepairs(1);
        // 第1轮:SQL 合法,语义审查 fail;第2轮(修复轮):语义审查 pass
        script(sqlPayload(GOOD_SQL), "{\"verdict\":\"fail\",\"reason\":\"口径不符\"}",
                sqlPayload(GOOD_SQL), "{\"verdict\":\"pass\"}");

        AnswerPayload p = orchestrator.answer(1L, "今年总产奶量", null);

        assertEquals("ORCHESTRA", p.getEngine());
        assertTrue(p.getTrace().stream().anyMatch(t -> "reviewer(semantic)".equals(t.tool()) && !t.ok()));
        assertTrue(p.getTrace().stream().anyMatch(t -> "reviewer(semantic)".equals(t.tool()) && t.ok()));
    }

    @Test
    void exhaustedRepairBudgetThrowsForShellDowngrade() throws Exception {
        props.getChat().getOrchestra().setMaxRepairs(1);
        props.getChat().getOrchestra().setLlmReview(false);
        script(sqlPayload(WRONG_TABLE_SQL), sqlPayload(WRONG_TABLE_SQL), sqlPayload(WRONG_TABLE_SQL));

        BizException ex = assertThrows(BizException.class, () -> orchestrator.answer(1L, "今年总产奶量", null));
        assertTrue(ex.getMessage().contains("预算耗尽"), "预算耗尽信息应带最后拒绝原因,实际: " + ex.getMessage());
    }
}
