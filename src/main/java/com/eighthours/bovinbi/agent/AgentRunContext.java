package com.eighthours.bovinbi.agent;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.ExecResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一次问答的 Agent 运行上下文(请求级状态,三重职责):
 * 1) 预算账本:总调用 / SQL 执行 / Schema 获取 / 维度值查询各自限次 ——
 *    "自主性的边界在工具层强制",不依赖框架循环是否收敛;
 * 2) 结果登记:executeSql 成功的 SQL → 完整 ExecResult 映射,
 *    模型最终回答只需回填 SQL 原文,即可零成本取回结果,避免二次执行;
 * 3) 轨迹收集:所有工具调用(含失败)写入 trace,随 AnswerPayload 返回。
 *
 * 面试要点:SQL 执行预算是旧管线"自修复一次(限制单次)"的泛化 ——
 * 修复重试从硬编码的一次变成可配置、可统计的 N 次,由评测数据决定取值。
 */
public class AgentRunContext {

    private final Long datasetId;
    private final String question;
    private final BovinProperties.Agent cfg;

    private int seq;
    private int totalCalls;
    private int sqlCalls;
    private int schemaCalls;
    private int valueCalls;

    /** SQL 守护通过后的表白名单:首次 getSchema 时填充,executeSql/getColumnValues 的守护依赖它 */
    private Set<String> whitelist = Set.of();

    /** 已成功执行的结果:normalize(原始或守护后 SQL) → Executed;两个键都登记,容忍守护重排版差异 */
    private final Map<String, Executed> results = new LinkedHashMap<>();

    private final List<AgentTrace.ToolCall> trace = new ArrayList<>();

    /** 守护后 SQL 与其结果的绑定(guardedSql 用于对外展示,避免暴露模型原始写法) */
    public record Executed(String guardedSql, ExecResult result) {
    }

    public AgentRunContext(Long datasetId, String question, BovinProperties.Agent cfg) {
        this.datasetId = datasetId;
        this.question = question;
        this.cfg = cfg;
    }

    /**
     * 预算检查与扣减:通过返回 null;超限返回给模型的"拒绝话术"。
     * 设计:超限不抛异常 —— 错误即数据,让模型读到限制后自行收尾输出最终回答。
     */
    public String tryConsume(String tool) {
        totalCalls++;
        if (totalCalls > cfg.getMaxToolCalls()) {
            return "工具调用总预算(%d 次)已耗尽,请立即基于已有信息输出最终 JSON 回答".formatted(cfg.getMaxToolCalls());
        }
        return switch (tool) {
            case "executeSql" -> ++sqlCalls > cfg.getMaxSqlExecutions()
                    ? "SQL 执行次数已达上限(%d 次),请基于已有结果输出最终 JSON 回答".formatted(cfg.getMaxSqlExecutions())
                    : null;
            case "getSchema" -> ++schemaCalls > cfg.getMaxSchemaCalls()
                    ? "getSchema 调用已达上限(%d 次),请基于已获取的 Schema 继续".formatted(cfg.getMaxSchemaCalls())
                    : null;
            case "getColumnValues" -> ++valueCalls > cfg.getMaxValueCalls()
                    ? "getColumnValues 调用已达上限(%d 次)".formatted(cfg.getMaxValueCalls())
                    : null;
            default -> null;
        };
    }

    public int nextSeq() {
        return ++seq;
    }

    public void record(AgentTrace.ToolCall call) {
        if (trace.size() < 64) {
            trace.add(call);
        }
    }

    /** 双键登记:模型原始 SQL 与守护重排后的 SQL 都指向同一份结果,最终回填任一形式都能命中 */
    public void markResult(String originalSql, String guardedSql, ExecResult result) {
        Executed executed = new Executed(guardedSql, result);
        results.put(normalizeSql(originalSql), executed);
        results.put(normalizeSql(guardedSql), executed);
    }

    public Executed findResult(String sql) {
        return sql == null ? null : results.get(normalizeSql(sql));
    }

    /** SQL 归一化:大小写与空白折叠后比对,容忍守护重排版的差异 */
    public static String normalizeSql(String sql) {
        return sql.toLowerCase().replaceAll("\\s+", " ").trim();
    }

    public void whitelist(Set<String> w) {
        if (w != null && !w.isEmpty()) {
            this.whitelist = w;
        }
    }

    public Long datasetId() {
        return datasetId;
    }

    public String question() {
        return question;
    }

    public BovinProperties.Agent cfg() {
        return cfg;
    }

    public Set<String> whitelist() {
        return whitelist;
    }

    public List<AgentTrace.ToolCall> trace() {
        return trace;
    }
}
