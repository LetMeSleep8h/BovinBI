package com.eighthours.bovinbi.agent;

import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.service.QueryExecutor;
import com.eighthours.bovinbi.service.SchemaRetriever;
import com.eighthours.bovinbi.service.SqlGuard;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.regex.Pattern;

/**
 * Agent 工具集(模型可调用的全部能力,安全边界全部内建在工具里):
 * 1) getSchema:Schema 召回(复用管线的 SchemaRetriever 接口),并把表白名单登记进运行上下文;
 * 2) getColumnValues:维度值字典查询(防模型编造维度过滤值);
 * 3) executeSql:唯一执行入口,内置 AST 守护 + 表白名单 + 强制 LIMIT + 只读超时。
 *
 * 三条设计原则(面试要点):
 * - 提示词是建议,工具是法律:模型可以输出任意 SQL,能否执行由 SqlGuard 决定,不靠模型自觉;
 * - 错误即数据:守护拒绝/执行报错都以文本返回给模型,驱动其阅读报错自修复
 *   —— 取代旧管线硬编码的"自修复一次",修复次数由预算统一约束;
 * - 预算在工具层强制:总调用/SQL 执行等次数超限直接拒绝,框架循环因此天然有界,
 *   即使模型陷入"反复重试"也不会失控烧钱。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BovinTools {

    /** 维度值查询的表/列名只接受简单标识符,从源头杜绝标识符注入 */
    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final SchemaRetriever schemaRetriever;
    private final SqlGuard sqlGuard;
    private final QueryExecutor queryExecutor;

    @Tool("获取数据集的表结构与业务字段说明(字段业务名/口径/聚合方式/同义词)。写任何 SQL 之前必须先调用本工具;可传入问题关键词提高字段召回排序。")
    public String getSchema(String keywords) {
        AgentRunContext ctx = requireContext();
        String refusal = ctx.tryConsume("getSchema");
        if (refusal != null) {
            return refusal;
        }
        long t0 = System.currentTimeMillis();
        String args = "keywords=" + brief(keywords);
        try {
            SchemaRetriever.LinkedSchema linked = schemaRetriever.retrieve(ctx.datasetId(),
                    keywords == null || keywords.isBlank() ? ctx.question() : keywords);
            ctx.whitelist(linked.whitelist());
            ctx.record(new AgentTrace.ToolCall(ctx.nextSeq(), "getSchema", args, true,
                    elapsed(t0), "白名单:" + linked.whitelist()));
            return linked.schemaText();
        } catch (Exception e) {
            ctx.record(new AgentTrace.ToolCall(ctx.nextSeq(), "getSchema", args, false, elapsed(t0), brief(e.getMessage())));
            return "获取 Schema 失败: " + e.getMessage();
        }
    }

    @Tool("查询某个维度字段在库中的真实可选值(如牧场名/品种/地区),用于生成准确的 WHERE 条件,防止编造维度值。table 与 column 必须来自 Schema。")
    public String getColumnValues(String tableName, String columnName, String keyword) {
        AgentRunContext ctx = requireContext();
        String refusal = ctx.tryConsume("getColumnValues");
        if (refusal != null) {
            return refusal;
        }
        if (ctx.whitelist().isEmpty()) {
            return "尚未获取 Schema,请先调用 getSchema,再查询维度值";
        }
        if (tableName == null || columnName == null
                || !IDENTIFIER.matcher(tableName).matches()
                || !IDENTIFIER.matcher(columnName).matches()) {
            return "参数非法:table/column 必须是简单标识符(字母/数字/下划线),且来自 Schema";
        }
        String kw = keyword == null ? "" : keyword.replace("'", "").trim();
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ").append(columnName).append(" AS v FROM ").append(tableName);
        if (!kw.isBlank()) {
            sql.append(" WHERE ").append(columnName).append(" LIKE '%").append(kw).append("%'");
        }
        sql.append(" ORDER BY 1 LIMIT 20");

        long t0 = System.currentTimeMillis();
        String args = "%s.%s kw=%s".formatted(tableName, columnName, brief(keyword));
        try {
            String guarded = sqlGuard.validate(sql.toString(), ctx.whitelist());
            ExecResult r = queryExecutor.execute(guarded);
            String values = r.rows().stream()
                    .map(row -> String.valueOf(row.get("v")))
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("(无数据)");
            String summary = "返回 " + r.rowCount() + " 个可选值";
            ctx.record(new AgentTrace.ToolCall(ctx.nextSeq(), "getColumnValues", args, true, elapsed(t0), summary));
            return "%s.%s 可选值(最多显示 20 个): %s".formatted(tableName, columnName, values);
        } catch (Exception e) {
            ctx.record(new AgentTrace.ToolCall(ctx.nextSeq(), "getColumnValues", args, false, elapsed(t0), brief(e.getMessage())));
            return "维度值查询失败: " + e.getMessage() + "(请确认表名/列名来自 Schema)";
        }
    }

    @Tool("执行一条只读 SELECT 查询并返回结果预览(列名+前若干行)。SQL 会经过安全守护:仅单条 SELECT/表白名单/强制 LIMIT。执行失败会返回报错原因,请阅读报错修正后重试(注意 SQL 执行次数有上限)。")
    public String executeSql(String sql) {
        AgentRunContext ctx = requireContext();
        String refusal = ctx.tryConsume("executeSql");
        if (refusal != null) {
            return refusal;
        }
        if (ctx.whitelist().isEmpty()) {
            return "尚未获取 Schema,请先调用 getSchema 了解表结构,再生成 SQL";
        }
        long t0 = System.currentTimeMillis();
        String args = "sql=" + brief(sql);
        try {
            String guarded = sqlGuard.validate(sql, ctx.whitelist());
            try {
                ExecResult r = queryExecutor.execute(guarded);
                ctx.markResult(sql, guarded, r);
                String preview = preview(r, ctx.cfg().getPreviewRows());
                ctx.record(new AgentTrace.ToolCall(ctx.nextSeq(), "executeSql", args, true, elapsed(t0),
                        r.rowCount() + " 行结果"));
                return preview;
            } catch (Exception execError) {
                ctx.record(new AgentTrace.ToolCall(ctx.nextSeq(), "executeSql", args, false, elapsed(t0), brief(execError.getMessage())));
                return "执行失败: " + rootMessage(execError)
                        + "\n请修正 SQL 后重试(常见修法:列名以 Schema 为准;聚合条件放 HAVING 而非 WHERE;别名引用改为重复表达式)";
            }
        } catch (Exception guardError) {
            ctx.record(new AgentTrace.ToolCall(ctx.nextSeq(), "executeSql", args, false, elapsed(t0), brief(guardError.getMessage())));
            return "守护拒绝: " + guardError.getMessage() + "\n请按硬约束改写后重试(仅单条 SELECT,只使用 Schema 中的表和列)";
        }
    }

    /** 上下文缺失属于编程错误(必须经由 AgentOrchestrator 进入),直接抛异常暴露问题 */
    private AgentRunContext requireContext() {
        AgentRunContext ctx = AgentContextHolder.get();
        if (ctx == null) {
            throw new IllegalStateException("AgentRunContext 未初始化:工具只能由 AgentOrchestrator 发起的循环内调用");
        }
        return ctx;
    }

    /** 结果预览:控制回喂 token —— 列名 + 前 N 行,行内值以 | 分隔 */
    private String preview(ExecResult r, int maxRows) {
        StringBuilder sb = new StringBuilder("执行成功:共 ").append(r.rowCount()).append(" 行,耗时 ").append(r.tookMs()).append(" ms\n");
        String cols = r.columns().stream().map(c -> c.name()).reduce((a, b) -> a + " | " + b).orElse("");
        sb.append("列: ").append(cols).append('\n');
        sb.append("前 ").append(Math.min(maxRows, r.rows().size())).append(" 行:\n");
        r.rows().stream().limit(maxRows).forEach(row -> {
            String line = row.values().stream().map(v -> v == null ? "" : String.valueOf(v)).reduce((a, b) -> a + " | " + b).orElse("");
            sb.append(line).append('\n');
        });
        return sb.toString();
    }

    private String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null) {
            t = t.getCause();
        }
        return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
    }

    private static int elapsed(long t0) {
        return (int) (System.currentTimeMillis() - t0);
    }

    private static String brief(String s) {
        if (s == null) {
            return "";
        }
        String oneLine = s.replaceAll("\\s+", " ").trim();
        return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 120) + "…";
    }
}
