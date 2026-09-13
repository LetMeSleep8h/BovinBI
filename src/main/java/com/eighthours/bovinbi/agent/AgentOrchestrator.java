package com.eighthours.bovinbi.agent;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.llm.LlmClient;
import com.eighthours.bovinbi.service.QueryExecutor;
import com.eighthours.bovinbi.service.RuleSqlGenerator;
import com.eighthours.bovinbi.service.SchemaLinker;
import com.eighthours.bovinbi.service.SqlGenContext;
import com.eighthours.bovinbi.service.SqlGuard;
import com.eighthours.bovinbi.service.SqlResult;
import com.eighthours.bovinbi.service.TimeRange;
import com.eighthours.bovinbi.service.TimeRangeParser;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Set;

/**
 * Agent 编排层:在旧管线的外壳里运行 Agent(闲聊分流/语义缓存/图表推荐仍留在 Nl2SqlService,职责不变)。
 * 1) 确定性信息前置:时间解析、今天日期以 SideInfo 注入用户消息 —— 概率性的事交给模型,确定性的交给代码;
 * 2) 建立 ThreadLocal 运行上下文(预算/白名单/结果登记/轨迹) → 调用 BovinAgent 完成工具循环 → finally 清理;
 * 3) 结果回填:final_sql 必须命中上下文中"已成功执行"的登记,直接取回结果;
 *    取不到(模型改写/幻觉)则"信任但校验":重新守护+执行一次,仍失败降级 —— 绝不盲信模型输出;
 * 4) 兜底:Agent 链路任何一环失败 → 规则引擎,对外依然只有"可用回答"或"优雅降级"两种结局。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentOrchestrator {

    private static final String FALLBACK_HINT = "抱歉,我暂时理解不了这个问题。可以试试:每月产奶量趋势 / 产奶量Top10牧场 / 上个月各品种产奶量占比";

    private final ObjectProvider<BovinAgent> agentProvider;
    private final TimeRangeParser timeRangeParser;
    private final SchemaLinker schemaLinker;
    private final RuleSqlGenerator ruleSqlGenerator;
    private final SqlGuard sqlGuard;
    private final QueryExecutor queryExecutor;
    private final LlmClient llmClient;
    private final BovinProperties props;

    /** @param sessionId 会话 id(agent 记忆锚点);为 null(如评测单轮跑批)时生成一次性 id,避免题目间记忆串扰 */
    public AnswerPayload answer(Long datasetId, String question, Long sessionId) {
        long t0 = System.currentTimeMillis();
        AgentRunContext ctx = new AgentRunContext(datasetId, question, props.getChat().getAgent());

        BovinAgent agent = agentProvider.getIfAvailable();
        if (agent == null) {
            log.warn("engine=agent 但 provider 非 openai,BovinAgent 未装配,直接走规则引擎兜底");
            return ruleFallback(ctx, t0, "AGENT(未配置LLM→RULE)");
        }
        try {
            TimeRange tr = timeRangeParser.parse(question);
            String raw = runLoop(agent, ctx, sessionId, tr);
            return assemble(ctx, raw, t0);
        } catch (Exception e) {
            log.warn("Agent 链路失败,降级规则引擎: {}", e.getMessage());
            return ruleFallback(ctx, t0, "AGENT(降级RULE)");
        }
    }

    /** 建立 ThreadLocal 上下文后进入工具循环;finally 清理是硬约束,异常路径也不允许泄漏 */
    private String runLoop(BovinAgent agent, AgentRunContext ctx, Long sessionId, TimeRange tr) {
        String userMessage = """
                #SideInfo: %s
                #Question: %s""".formatted(sideInfo(tr), ctx.question());
        long memoryId = sessionId == null ? System.nanoTime() : sessionId;
        AgentContextHolder.set(ctx);
        try {
            return agent.chat(memoryId, userMessage);
        } finally {
            AgentContextHolder.clear();
        }
    }

    private String sideInfo(TimeRange tr) {
        return "今天日期: " + LocalDate.now()
                + ";时间理解: " + (tr == null
                ? "未识别到明确时间,默认不添加时间过滤"
                : "已解析为区间 [" + tr.start() + ", " + tr.endExclusive() + ") 标签:" + tr.label());
    }

    /** 解析最终 JSON → 回填已执行结果;final_sql 对不上登记则守护+执行一次(信任但校验) */
    private AnswerPayload assemble(AgentRunContext ctx, String raw, long t0) {
        JsonNode json = llmClient.extractJson(raw);
        String finalSql = json.path("final_sql").asText("").trim();
        String explanation = json.path("explanation").asText("");
        if (finalSql.isEmpty()) {
            throw new BizException(502, "Agent 最终回答缺少 final_sql");
        }

        String guardedSql = finalSql;
        ExecResult result;
        AgentRunContext.Executed executed = ctx.findResult(finalSql);
        if (executed != null) {
            guardedSql = executed.guardedSql();
            result = executed.result();
        } else {
            // 模型改写了已执行 SQL 或幻觉:重新走完整守护+执行,失败则整体降级
            Set<String> whitelist = ctx.whitelist().isEmpty()
                    ? schemaLinker.link(ctx.datasetId(), ctx.question()).whitelist()
                    : ctx.whitelist();
            guardedSql = sqlGuard.validate(finalSql, whitelist);
            result = queryExecutor.execute(guardedSql);
            log.info("final_sql 未命中已执行登记,已重新守护执行");
        }

        AnswerPayload p = new AnswerPayload();
        p.setSql(guardedSql);
        p.setExplanation(explanation);
        p.setColumns(result.columns());
        p.setRows(result.rows());
        p.setRowCount(result.rowCount());
        p.setEngine("AGENT");
        p.setTrace(ctx.trace());
        p.setTookMs(System.currentTimeMillis() - t0);
        return p;
    }

    /** 规则引擎兜底:Agent 链路失败后的最终防线,产出规则 SQL 或优雅降级提示 */
    private AnswerPayload ruleFallback(AgentRunContext ctx, long t0, String engine) {
        AnswerPayload p = new AnswerPayload();
        p.setFallback(true);
        p.setFallbackHint(FALLBACK_HINT);
        p.setEngine(engine);
        p.setTrace(ctx.trace());
        p.setTookMs(System.currentTimeMillis() - t0);
        try {
            TimeRange tr = timeRangeParser.parse(ctx.question());
            SchemaLinker.LinkedSchema linked = schemaLinker.link(ctx.datasetId(), ctx.question());
            SqlResult r = ruleSqlGenerator.generate(new SqlGenContext(ctx.question(), tr, linked.schemaText(), linked.whitelist()));
            if (r != null && r.sql() != null && !r.sql().isBlank()) {
                String guarded = sqlGuard.validate(r.sql(), linked.whitelist());
                ExecResult result = queryExecutor.execute(guarded);
                p.setFallback(false);
                p.setFallbackHint(null);
                p.setSql(guarded);
                p.setExplanation(r.explanation());
                p.setColumns(result.columns());
                p.setRows(result.rows());
                p.setRowCount(result.rowCount());
            }
        } catch (Exception e) {
            log.warn("规则引擎兜底也失败: {}", e.getMessage());
        }
        return p;
    }
}
