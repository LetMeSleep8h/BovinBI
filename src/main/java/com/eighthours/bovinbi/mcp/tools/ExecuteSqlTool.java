package com.eighthours.bovinbi.mcp.tools;

import com.eighthours.bovinbi.agent.AgentContextHolder;
import com.eighthours.bovinbi.agent.AgentRunContext;
import com.eighthours.bovinbi.dto.ColInfo;
import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.mcp.McpTool;
import com.eighthours.bovinbi.mcp.McpToolDefinition;
import com.eighthours.bovinbi.mcp.McpToolResult;
import com.eighthours.bovinbi.service.QueryExecutor;
import com.eighthours.bovinbi.service.SqlGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * executeSql 工具:唯一 SQL 执行入口,内置 AST 守护 + 表白名单 + 强制 LIMIT + 只读超时。
 * "提示词是建议,工具是法律":能否执行由 SqlGuard 决定,不靠模型自觉;
 * 守护拒绝/执行报错都以 isError + 文本返回(错误即数据),驱动模型自修复。
 */
@Component
@RequiredArgsConstructor
public class ExecuteSqlTool implements McpTool {

    private final SqlGuard sqlGuard;
    private final QueryExecutor queryExecutor;
    private final ToolSupport support;

    @Override
    public McpToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sql", McpToolDefinition.property("string", "只读 SELECT 语句,表和列必须来自 getSchema 返回的 Schema"));
        props.put("datasetId", McpToolDefinition.property("integer", "数据集 id;Agent 循环内可省略"));
        return McpToolDefinition.of("executeSql",
                "执行一条只读 SELECT 查询并返回结果预览(列名+前若干行)。SQL 会经过安全守护:"
                        + "仅单条 SELECT/表白名单/强制 LIMIT。执行失败会返回报错原因,请阅读报错修正后重试"
                        + "(注意 SQL 执行次数有上限)。",
                props, List.of("sql"));
    }

    @Override
    public McpToolResult execute(Map<String, Object> arguments) {
        AgentRunContext ctx = AgentContextHolder.get();
        if (ctx != null && ctx.whitelist().isEmpty()) {
            return McpToolResult.error("尚未获取 Schema,请先调用 getSchema 了解表结构,再生成 SQL");
        }
        String sql = ToolSupport.str(arguments, "sql");
        if (sql == null || sql.isBlank()) {
            return McpToolResult.error("参数非法:sql 不能为空");
        }
        Long datasetId = support.resolveDatasetId(arguments, ctx);
        if (datasetId == null) {
            return McpToolResult.error("缺少 datasetId 参数:Agent 循环外调用(如 MCP 客户端)必须显式指定数据集");
        }
        try {
            String guarded = sqlGuard.validate(sql, support.resolveWhitelist(datasetId, ctx));
            try {
                ExecResult r = queryExecutor.execute(guarded);
                if (ctx != null) {
                    ctx.markResult(sql, guarded, r); // 最终回答回填校验依据(双键登记)
                }
                return McpToolResult.text(preview(r, ctx == null ? 8 : ctx.cfg().getPreviewRows()));
            } catch (Exception execError) {
                return McpToolResult.error("执行失败: " + rootMessage(execError)
                        + "\n请修正 SQL 后重试(常见修法:列名以 Schema 为准;聚合条件放 HAVING 而非 WHERE;别名引用改为重复表达式)");
            }
        } catch (Exception guardError) {
            return McpToolResult.error("守护拒绝: " + guardError.getMessage()
                    + "\n请按硬约束改写后重试(仅单条 SELECT,只使用 Schema 中的表和列)");
        }
    }

    /** 结果预览:控制回喂 token —— 列名 + 前 N 行,行内值以 | 分隔 */
    private String preview(ExecResult r, int maxRows) {
        StringBuilder sb = new StringBuilder("执行成功:共 ").append(r.rowCount()).append(" 行,耗时 ")
                .append(r.tookMs()).append(" ms\n");
        String cols = r.columns().stream().map(ColInfo::name).reduce((a, b) -> a + " | " + b).orElse("");
        sb.append("列: ").append(cols).append('\n');
        sb.append("前 ").append(Math.min(maxRows, r.rows().size())).append(" 行:\n");
        r.rows().stream().limit(maxRows).forEach(row -> {
            String line = row.values().stream().map(v -> v == null ? "" : String.valueOf(v))
                    .reduce((a, b) -> a + " | " + b).orElse("");
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
}
