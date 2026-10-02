package com.eighthours.bovinbi.mcp.tools;

import com.eighthours.bovinbi.agent.AgentContextHolder;
import com.eighthours.bovinbi.agent.AgentRunContext;
import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.mcp.McpTool;
import com.eighthours.bovinbi.mcp.McpToolDefinition;
import com.eighthours.bovinbi.mcp.McpToolResult;
import com.eighthours.bovinbi.service.QueryExecutor;
import com.eighthours.bovinbi.service.SqlGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * getColumnValues 工具:维度值字典查询,防模型编造 WHERE 过滤值。
 * 标识符只接受简单标识符(从源头杜绝标识符注入),产物 SQL 仍过守护 —— 双保险。
 */
@Component
@RequiredArgsConstructor
public class GetColumnValuesTool implements McpTool {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final SqlGuard sqlGuard;
    private final QueryExecutor queryExecutor;
    private final ToolSupport support;

    @Override
    public McpToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tableName", McpToolDefinition.property("string", "物理表名,必须来自 Schema"));
        props.put("columnName", McpToolDefinition.property("string", "维度列名,必须来自 Schema"));
        props.put("keyword", McpToolDefinition.property("string", "过滤关键词;可空"));
        props.put("datasetId", McpToolDefinition.property("integer", "数据集 id;Agent 循环内可省略"));
        return McpToolDefinition.of("getColumnValues",
                "查询某个维度字段在库中的真实可选值(如牧场名/品种/地区),用于生成准确的 WHERE 条件,防止编造维度值。",
                props, java.util.List.of("tableName", "columnName"));
    }

    @Override
    public McpToolResult execute(Map<String, Object> arguments) {
        AgentRunContext ctx = AgentContextHolder.get();
        if (ctx != null && ctx.whitelist().isEmpty()) {
            return McpToolResult.error("尚未获取 Schema,请先调用 getSchema,再查询维度值");
        }
        String tableName = ToolSupport.str(arguments, "tableName");
        String columnName = ToolSupport.str(arguments, "columnName");
        if (tableName == null || columnName == null
                || !IDENTIFIER.matcher(tableName).matches()
                || !IDENTIFIER.matcher(columnName).matches()) {
            return McpToolResult.error("参数非法:table/column 必须是简单标识符(字母/数字/下划线),且来自 Schema");
        }
        Long datasetId = support.resolveDatasetId(arguments, ctx);
        if (datasetId == null) {
            return McpToolResult.error("缺少 datasetId 参数:Agent 循环外调用(如 MCP 客户端)必须显式指定数据集");
        }
        String kw = arguments.get("keyword") == null ? "" : String.valueOf(arguments.get("keyword")).replace("'", "").trim();
        StringBuilder sql = new StringBuilder("SELECT DISTINCT ").append(columnName).append(" AS v FROM ").append(tableName);
        if (!kw.isBlank()) {
            sql.append(" WHERE ").append(columnName).append(" LIKE '%").append(kw).append("%'");
        }
        sql.append(" ORDER BY 1 LIMIT 20");

        try {
            String guarded = sqlGuard.validate(sql.toString(), support.resolveWhitelist(datasetId, ctx));
            ExecResult r = queryExecutor.execute(guarded);
            String values = r.rows().stream()
                    .map(row -> String.valueOf(row.get("v")))
                    .reduce((a, b) -> a + " | " + b)
                    .orElse("(无数据)");
            return McpToolResult.text("%s.%s 可选值(最多显示 20 个): %s".formatted(tableName, columnName, values));
        } catch (Exception e) {
            return McpToolResult.error("维度值查询失败: " + e.getMessage() + "(请确认表名/列名来自 Schema)");
        }
    }
}
