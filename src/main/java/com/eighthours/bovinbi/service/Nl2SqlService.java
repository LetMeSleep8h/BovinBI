package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.ChartSpec;
import com.eighthours.bovinbi.dto.ExecResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * NL2SQL 查询管线(编排层),六步主链路:
 * 0.闲聊分流 → 1.时间解析 → 2.语义缓存 → 3.Schema 召回 → 4~5.SQL 生成+守护+执行 → 6.图表推荐
 *
 * 引擎降级链(第 4~5 步内部,是全项目唯一的双引擎交汇点):
 *   LLM 生成 → [失败]→ 规则引擎
 *   LLM 生成 → SQL 守护/执行 [失败]→ LLM 自修复一次 → [仍失败]→ 规则引擎
 * 无论链路上哪一环出错,对外只有两种结果:可用回答,或"无法理解"的优雅降级。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class Nl2SqlService {

    private final TimeRangeParser timeRangeParser;
    private final ChitChatHandler chitChatHandler;
    private final SchemaLinker schemaLinker;
    private final SemanticCache semanticCache;
    private final RuleSqlGenerator ruleSqlGenerator;
    private final LlmSqlGenerator llmSqlGenerator;
    private final SqlGuard sqlGuard;
    private final QueryExecutor queryExecutor;
    private final ChartAdvisor chartAdvisor;
    private final BovinProperties props;

    public AnswerPayload answer(Long datasetId, String question) {
        long t0 = System.currentTimeMillis();

        // 步骤 0:意图分流 —— 闲聊/能力询问不进管线
        if (chitChatHandler.isChitChat(question)) {
            return chitChatHandler.answer(question);
        }

        // 步骤 1:时间解析(确定性规则,半开区间;null = 无时间语义)
        TimeRange timeRange = timeRangeParser.parse(question);

        // 步骤 2:语义缓存 —— 归一化问题命中则跳过生成与执行
        String cacheKey = semanticCache.key(datasetId, question, timeRange);
        AnswerPayload cached = semanticCache.get(cacheKey);
        if (cached != null) {
            return cacheHit(cached, t0);
        }

        // 步骤 3:Schema 召回 —— 紧凑 Schema 文本 + 表白名单(一次查询带出)
        SchemaLinker.LinkedSchema schema = schemaLinker.link(datasetId, question);
        SqlGenContext ctx = new SqlGenContext(question, timeRange, schema.schemaText(), schema.whitelist());

        // 步骤 4~5:生成 + 守护 + 执行(双引擎降级链;executed 为 null = 无法理解)
        Answer answer = generateValidateExecute(ctx);
        if (answer.executed() == null) {
            return fallbackPayload(t0, answer.engine());
        }

        // 步骤 6:图表推荐 + 组装载荷,成功结果写入缓存
        ExecResult result = answer.executed().result();
        ChartSpec chart = chartAdvisor.advise(question, result.columns(), result.rows());
        AnswerPayload payload = new AnswerPayload();
        payload.setSql(answer.executed().sql());
        payload.setExplanation(answer.explanation());
        payload.setColumns(result.columns());
        payload.setRows(result.rows());
        payload.setRowCount(result.rowCount());
        payload.setChart(chart);
        payload.setTookMs(System.currentTimeMillis() - t0);
        payload.setEngine(answer.engine());
        semanticCache.put(cacheKey, payload);
        return payload;
    }

    /** 守护+执行产物:sql 为守护后的最终 SQL(含强制 LIMIT),result 为结果集 */
    private record Executed(String sql, ExecResult result) {
    }

    /** 一次问答的最终产物:executed 为 null 表示"无法理解该问题" */
    private record Answer(Executed executed, String explanation, String engine) {
    }

    /**
     * 双引擎降级链:LLM 优先;生成失败走规则;执行失败先自修复一次,再失败走规则;
     * provider=mock 时规则引擎即主引擎。规则引擎也认不出的问题返回 executed=null。
     */
    private Answer generateValidateExecute(SqlGenContext ctx) {
        boolean llmMode = "openai".equalsIgnoreCase(props.getLlm().getProvider());
        if (!llmMode) {
            return byRule(ctx, "RULE");
        }

        SqlResult generated = null;
        try {
            generated = llmSqlGenerator.generate(ctx);
            Executed ok = validateAndExecute(generated.sql(), ctx);
            return new Answer(ok, generated.explanation(), "LLM");
        } catch (Exception firstError) {
            if (!props.getChat().isFallbackToRule()) {
                throw firstError instanceof BizException biz ? biz
                        : new BizException(502, firstError.getMessage());
            }
            log.warn("LLM 主链路失败: {}", firstError.getMessage());
            // 只有已经拿到 SQL(守护/执行阶段失败)才值得自修复;修复仍失败则落到规则引擎
            if (generated != null) {
                try {
                    SqlResult fixed = llmSqlGenerator.repair(ctx, generated.sql(), firstError.getMessage());
                    Executed ok = validateAndExecute(fixed.sql(), ctx);
                    return new Answer(ok, fixed.explanation(), "LLM(修复)");
                } catch (Exception repairError) {
                    log.warn("LLM 自修复仍失败,降级规则引擎: {}", repairError.getMessage());
                }
            }
        }
        return byRule(ctx, "RULE(降级)");
    }

    /** 规则引擎路径:生成 → 守护 → 执行;认不出问题返回 executed=null */
    private Answer byRule(SqlGenContext ctx, String engine) {
        SqlResult r = ruleSqlGenerator.generate(ctx);
        if (r == null || r.sql() == null || r.sql().isBlank()) {
            return new Answer(null, null, engine);
        }
        return new Answer(validateAndExecute(r.sql(), ctx), r.explanation(), engine);
    }

    /** SQL 守护(AST 校验/表白名单/强制 LIMIT)通过后交只读执行器 */
    private Executed validateAndExecute(String sql, SqlGenContext ctx) {
        String guarded = sqlGuard.validate(sql, ctx.whitelist());
        return new Executed(guarded, queryExecutor.execute(guarded));
    }

    /** 缓存命中:显式拷贝字段,避免调用方改动污染缓存对象 */
    private AnswerPayload cacheHit(AnswerPayload c, long t0) {
        AnswerPayload hit = new AnswerPayload();
        hit.setSql(c.getSql());
        hit.setExplanation(c.getExplanation());
        hit.setColumns(c.getColumns());
        hit.setRows(c.getRows());
        hit.setRowCount(c.getRowCount());
        hit.setChart(c.getChart());
        hit.setCacheHit(true);
        hit.setEngine("CACHE");
        hit.setTookMs(System.currentTimeMillis() - t0);
        log.info("语义缓存命中: {}", c.getSql());
        return hit;
    }

    private AnswerPayload fallbackPayload(long t0, String engine) {
        AnswerPayload p = new AnswerPayload();
        p.setFallback(true);
        p.setFallbackHint("抱歉,我暂时理解不了这个问题。可以试试:每月产奶量趋势 / 产奶量Top10牧场 / 上个月各品种产奶量占比");
        p.setEngine(engine);
        p.setTookMs(System.currentTimeMillis() - t0);
        return p;
    }
}
