package com.eighthours.bovinbi.agent;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.ColInfo;
import com.eighthours.bovinbi.dto.ExecResult;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 运行上下文单测:固化两条核心契约 ——
 * 1) 预算在工具层强制:各类计数超限返回"拒绝话术"(错误即数据,不抛异常);
 * 2) 结果双键登记:模型原始 SQL 与守护重排后的 SQL 都能命中同一份结果。
 */
class AgentRunContextTest {

    private AgentRunContext ctx() {
        return new AgentRunContext(1L, "每月产奶量趋势", new BovinProperties().getChat().getAgent());
    }

    @Test
    void sqlBudgetRefusesAfterLimitAsData() {
        AgentRunContext ctx = ctx();
        for (int i = 0; i < 3; i++) {
            assertNull(ctx.tryConsume("executeSql"), "前 3 次 SQL 执行应放行");
        }
        String refusal = ctx.tryConsume("executeSql");
        assertNotNull(refusal, "第 4 次应被拒绝");
        assertTrue(refusal.contains("上限"));
        assertTrue(refusal.contains("最终 JSON"), "拒绝话术要引导模型收尾,而不是干等");
    }

    @Test
    void totalBudgetDominatesPerToolBudget() {
        // 单项预算放大(模拟配置演进/新增工具),总预算仍是成本硬顶:无论怎么配,最坏情况有界
        BovinProperties props = new BovinProperties();
        props.getChat().getAgent().setMaxValueCalls(20);
        props.getChat().getAgent().setMaxToolCalls(12);
        AgentRunContext ctx = new AgentRunContext(1L, "问题", props.getChat().getAgent());
        for (int i = 0; i < 12; i++) {
            assertNull(ctx.tryConsume("getColumnValues"), "总预算内应放行");
        }
        String refusal = ctx.tryConsume("executeSql");
        assertNotNull(refusal, "总预算耗尽后,任何工具都应被拒绝");
        assertTrue(refusal.contains("总预算"));
    }

    @Test
    void doubleKeyResultLookupToleratesGuardReprint() {
        AgentRunContext ctx = ctx();
        ExecResult r = new ExecResult(List.of(new ColInfo("产奶量", "DECIMAL")), List.of(), 0, 3);
        String modelSql = "select round(sum(m.milk_yield),2) as 产奶量 from dwh_fact_milk m limit 1000";
        String guardedSql = "SELECT ROUND(SUM(m.milk_yield), 2) AS 产奶量 FROM dwh_fact_milk m LIMIT 1000";
        ctx.markResult(modelSql, guardedSql, r);

        assertSame(r, ctx.findResult(modelSql).result(), "模型原文(即使大小写/空白不同)必须命中");
        assertSame(r, ctx.findResult(guardedSql).result(), "守护重排版必须命中");
        assertEquals(guardedSql, ctx.findResult(modelSql).guardedSql(), "对外展示守护后 SQL");
        assertNull(ctx.findResult("SELECT 1"));
    }
}
