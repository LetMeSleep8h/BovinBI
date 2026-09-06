package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.ChartSpec;
import com.eighthours.bovinbi.dto.ExecResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * NL2SQL 查询管线(编排层),六步:
 * 1.时间解析 → 2.语义缓存 → 3.Schema Linking → 4.SQL生成(双引擎) → 5.SQL守护 → 6.执行+图表推荐
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
    private final com.eighthours.bovinbi.mapper.DatasetMapper datasetMapper;
    private final ObjectMapper objectMapper;
    private final BovinProperties props;

    public AnswerPayload answer(Long datasetId, String question) {
        long t0 = System.currentTimeMillis();

        // 0. 意图分流:闲聊/能力询问不走 NL2SQL
        if (chitChatHandler.isChitChat(question)) {
            return chitChatHandler.answer(question);
        }

        TimeRange tr = timeRangeParser.parse(question);

        // 1. 语义缓存
        String key = semanticCache.key(datasetId, question, tr);
        AnswerPayload cached = semanticCache.get(key);
        if (cached != null) {
            AnswerPayload hit = deepCopy(cached);
            hit.setCacheHit(true);
            hit.setEngine("CACHE");
            hit.setTookMs(System.currentTimeMillis() - t0);
            log.info("语义缓存命中: {}", question);
            return hit;
        }

        // 2. Schema Linking
        SchemaLinker.LinkResult schema = schemaLinker.link(datasetId, question);
        SqlGenContext ctx = new SqlGenContext(question, tr, schema);

        // 3. SQL 生成(双引擎:LLM 主力 + 规则兜底)
        boolean useLlm = "openai".equalsIgnoreCase(props.getLlm().getProvider());
        SqlResult result;
        String engine;
        if (useLlm) {
            engine = "LLM";
            try {
                result = llmSqlGenerator.generate(ctx);
            } catch (Exception e) {
                if (!props.getChat().isFallbackToRule()) {
                    throw e;
                }
                log.warn("LLM 生成失败,降级到规则引擎: {}", e.getMessage());
                result = ruleSqlGenerator.generate(ctx);
                engine = "RULE(降级)";
            }
        } else {
            engine = "RULE";
            result = ruleSqlGenerator.generate(ctx);
        }

        // 4. 无法理解 → 优雅降级
        if (result == null || result.sql() == null || result.sql().isBlank()) {
            return fallbackPayload(t0, engine);
        }

        // 5-6. SQL 守护 + 执行;LLM 模式下执行/校验失败时,把数据库错误反馈给模型自修复一次
        Set<String> wl = whitelist(datasetId);
        String finalSql;
        ExecResult exec;
        try {
            finalSql = sqlGuard.validate(result.sql(), wl);
            exec = queryExecutor.execute(finalSql);
        } catch (BizException firstError) {
            if (!useLlm || !props.getChat().isFallbackToRule()) {
                throw firstError;
            }
            log.warn("SQL 校验/执行失败,尝试 LLM 自修复: {}", firstError.getMessage());
            SqlResult repaired = llmSqlGenerator.repair(ctx, result.sql(), firstError.getMessage());
            finalSql = sqlGuard.validate(repaired.sql(), wl);
            exec = queryExecutor.execute(finalSql);
            result = new SqlResult(finalSql, repaired.explanation() + "(SQL 已经过自动修复)", "LLM(修复)");
        }

        // 7. 图表推荐
        ChartSpec chart = chartAdvisor.advise(question, exec.columns(), exec.rows());

        AnswerPayload payload = new AnswerPayload();
        payload.setSql(finalSql);
        payload.setExplanation(result.explanation());
        payload.setColumns(exec.columns());
        payload.setRows(exec.rows());
        payload.setRowCount(exec.rowCount());
        payload.setChart(chart);
        payload.setTookMs(System.currentTimeMillis() - t0);
        payload.setCacheHit(false);
        payload.setEngine(engine);
        semanticCache.put(key, payload);
        return payload;
    }

    private AnswerPayload fallbackPayload(long t0, String engine) {
        AnswerPayload p = new AnswerPayload();
        p.setFallback(true);
        p.setFallbackHint("抱歉,我暂时理解不了这个问题。可以试试:每月产奶量趋势 / 产奶量Top10牧场 / 上个月各品种产奶量占比");
        p.setEngine(engine);
        p.setTookMs(System.currentTimeMillis() - t0);
        return p;
    }

    private Set<String> whitelist(Long datasetId) {
        var ds = datasetMapper.selectById(datasetId);
        if (ds == null || ds.getDwhTables() == null) {
            throw new BizException("数据集不存在或未配置表白名单");
        }
        return new HashSet<>(Arrays.asList(ds.getDwhTables().toLowerCase().split("[,，\\s]+")));
    }

    private AnswerPayload deepCopy(AnswerPayload src) {
        try {
            return objectMapper.readValue(objectMapper.writeValueAsString(src), AnswerPayload.class);
        } catch (Exception e) {
            return src;
        }
    }
}
