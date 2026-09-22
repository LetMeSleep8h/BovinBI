package com.eighthours.bovinbi.service.impl;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.ColInfo;
import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.llm.LlmClient;
import com.eighthours.bovinbi.service.QueryExecutor;
import com.eighthours.bovinbi.service.SchemaRetriever;
import com.eighthours.bovinbi.service.SqlGuard;
import com.eighthours.bovinbi.service.TimeRangeParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 多 Agent 流水线契约(单测:只桩 LlmClient,守护/时间解析/执行器用真实或 mock 边界):
 * 1) 守护拒绝 → Repair Agent 定向修复 → 成功,trace 含 repair_agent;
 * 2) 时间对账(确定性审查):SQL 时间区间与 TimeRangeParser 解析不一致 → 触发修复;
 * 3) LLM 语义审查 fail → 触发修复,审查 pass → 放行;
 * 4) 修复预算耗尽 → 抛出(外壳降级规则引擎)。
 */
class MultiAgentServiceImplTest {

    private SchemaRetriever schemaRetriever;
    private LlmClient llmClient;
    private QueryExecutor queryExecutor;
    private BovinProperties props;
    private MultiAgentServiceImpl service;
    private ObjectMapper objectMapper;

    private static final String GOOD_SQL =
            "SELECT SUM(m.milk_yield) AS 产奶量 FROM dwh_fact_milk m "
                    + "WHERE m.record_date >= '2026-01-01' AND m.record_date < '2027-01-01' LIMIT 1000";
    private static final String WRONG_TABLE_SQL = "SELECT * FROM secret_table LIMIT 10";
    private static final String WRONG_TIME_SQL =
            "SELECT SUM(m.milk_yield) AS 产奶量 FROM dwh_fact_milk m "
                    + "WHERE m.record_date >= '2020-01-01' AND m.record_date < '2021-01-01' LIMIT 1000";

    @BeforeEach
    void setUp() {
        schemaRetriever = mock(SchemaRetriever.class);
        llmClient = mock(LlmClient.class);
        queryExecutor = mock(QueryExecutor.class);
        props = new BovinProperties();
        objectMapper = new ObjectMapper();

        when(schemaRetriever.retrieve(anyLong(), anyString())).thenReturn(new SchemaRetriever.LinkedSchema(
                "【数据集】牧场养殖分析", Set.of("dwh_fact_milk", "dwh_dim_cattle", "dwh_dim_farm")));
        when(queryExecutor.execute(anyString())).thenReturn(oneRow());

        service = new MultiAgentServiceImpl(schemaRetriever, new TimeRangeParser(java.time.LocalDate.of(2026, 9, 5)),
                llmClient, new SqlGuard(props), queryExecutor, props);
    }

    /** LLM 桩:按脚本顺序回放响应;extractJson 用真实 ObjectMapper 解析 */
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
    void guardRejectionTriggersRepairAgent() throws Exception {
        props.getChat().getMultiAgent().setLlmReview(false);
        script(sqlPayload(WRONG_TABLE_SQL), sqlPayload(GOOD_SQL));

        AnswerPayload p = service.answer(1L, "今年总产奶量", null);

        assertEquals("MULTI_AGENT", p.getEngine());
        assertTrue(p.getSql().startsWith("SELECT"));
        assertTrue(p.getTrace().stream().anyMatch(t -> "repair_agent".equals(t.tool())),
                "守护拒绝后应由 Repair Agent 修复");
        assertTrue(p.getTrace().stream().anyMatch(t -> "reviewer".equals(t.tool()) && !t.ok()));
    }

    @Test
    void deterministicTimeMismatchTriggersRepair() throws Exception {
        props.getChat().getMultiAgent().setLlmReview(false);
        script(sqlPayload(WRONG_TIME_SQL), sqlPayload(GOOD_SQL));

        AnswerPayload p = service.answer(1L, "今年总产奶量", null);

        assertEquals("MULTI_AGENT", p.getEngine());
        assertTrue(p.getTrace().stream().anyMatch(t -> "reviewer".equals(t.tool()) && !t.ok()
                        && t.summary().contains("时间区间")),
                "确定性时间对账应拦截时间字面量不一致的 SQL");
    }

    @Test
    void llmReviewFailThenRepairPasses() throws Exception {
        props.getChat().getMultiAgent().setLlmReview(true);
        script(sqlPayload(GOOD_SQL),
                "{\"verdict\":\"fail\",\"reason\":\"口径不对\"}",
                sqlPayload(GOOD_SQL),
                "{\"verdict\":\"pass\",\"reason\":\"\"}");

        AnswerPayload p = service.answer(1L, "今年总产奶量", null);

        assertEquals("MULTI_AGENT", p.getEngine());
        assertTrue(p.getTrace().stream().anyMatch(t -> "reviewer".equals(t.tool()) && !t.ok()));
    }

    @Test
    void repairBudgetExhaustedThrowsForOuterFallback() throws Exception {
        props.getChat().getMultiAgent().setLlmReview(false);
        script(sqlPayload(WRONG_TABLE_SQL), sqlPayload(WRONG_TABLE_SQL), sqlPayload(WRONG_TABLE_SQL));

        BizException e = assertThrows(BizException.class, () -> service.answer(1L, "今年总产奶量", null));
        assertTrue(e.getMessage().contains("修复预算耗尽"));
    }

    @Test
    void noTimeQuestionWithTimeFilterGetsRejected() throws Exception {
        props.getChat().getMultiAgent().setLlmReview(false);
        String timeFiltered = "SELECT SUM(m.milk_yield) AS 产奶量 FROM dwh_fact_milk m "
                + "WHERE m.record_date >= '2026-01-01' AND m.record_date < '2027-01-01' LIMIT 1000";
        // 问题无时间语义("灰犀牛"占位无时间词),但 SQL 添加了时间过滤 → 确定性审查拦截 → 修复
        script(sqlPayload(timeFiltered), sqlPayload("SELECT SUM(m.milk_yield) AS 产奶量 FROM dwh_fact_milk m LIMIT 1000"));

        AnswerPayload p = service.answer(1L, "牧场总的产奶情况", null);

        assertEquals("MULTI_AGENT", p.getEngine());
        assertTrue(p.getTrace().stream().anyMatch(t -> "reviewer".equals(t.tool()) && !t.ok()
                        && t.summary().contains("不应添加")),
                "无时间问题不应带时间过滤");
    }

    @Test
    void repairAgentReceivesRejectionReason() throws Exception {
        props.getChat().getMultiAgent().setLlmReview(false);
        script(sqlPayload(WRONG_TABLE_SQL), sqlPayload(GOOD_SQL));
        service.answer(1L, "今年总产奶量", null);
        // 修复轮的用户消息里携带了拒绝原因(定向修复的证据)
        verify(llmClient).chat(anyString(), contains("未通过审查"));
    }
}
