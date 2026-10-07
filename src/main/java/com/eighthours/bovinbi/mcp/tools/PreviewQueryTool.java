package com.eighthours.bovinbi.mcp.tools;

import com.eighthours.bovinbi.agent.AgentContextHolder;
import com.eighthours.bovinbi.agent.AgentRunContext;
import com.eighthours.bovinbi.mcp.McpTool;
import com.eighthours.bovinbi.mcp.McpToolDefinition;
import com.eighthours.bovinbi.mcp.McpToolResult;
import com.eighthours.bovinbi.service.SqlGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * previewQuery 工具:SQL 干跑(dry-run)——只过守护并解析,不执行。
 * 场景:模型想先验证 SQL 语法/表列合法性再决定正式执行(省一次执行预算),
 * 或对多个候选 SQL 做筛选;也是外部 MCP 客户端的"SQL 体检"入口。
 * 与 executeSql 的差异:不消耗 SQL 执行预算、不产生结果登记。
 */
@Component
@RequiredArgsConstructor
public class PreviewQueryTool implements McpTool {

    private final ToolSupport support;
    private final SqlGuard sqlGuard;

    @Override
    public McpToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sql", McpToolDefinition.property("string", "待检查的 SELECT(不执行)"));
        props.put("datasetId", McpToolDefinition.property("integer", "数据集 id;Agent 循环内可省略"));
        return McpToolDefinition.of("previewQuery",
                "干跑校验一条 SQL:AST 解析/表白名单/强制 LIMIT 追加,返回守护后的 SQL 与结果列信息,"
                        + "但【不执行】、不消耗 SQL 执行预算。适合正式执行前自检或候选 SQL 筛选。",
                props, java.util.List.of("sql"));
    }

    @Override
    public McpToolResult execute(Map<String, Object> arguments) {
        String sql = ToolSupport.str(arguments, "sql");
        if (sql == null || sql.isBlank()) {
            return McpToolResult.error("参数非法:sql 不能为空");
        }
        AgentRunContext ctx = AgentContextHolder.get();
        Long datasetId = support.resolveDatasetId(arguments, ctx);
        if (datasetId == null) {
            return McpToolResult.error("缺少 datasetId 参数:Agent 循环外调用(如 MCP 客户端)必须显式指定数据集");
        }
        try {
            long t0 = System.currentTimeMillis();
            String guarded = sqlGuard.validate(sql, support.resolveWhitelist(datasetId, ctx));
            int approxCols = guarded.matches("(?si).*select\\s+\\*.*") ? -1
                    : guarded.split("(?i),").length; // 粗略列数提示,以 executeSql 实测为准
            return McpToolResult.text("校验通过(未执行)。守护后 SQL:\n" + guarded
                    + "\n提示:近似选择列数 " + approxCols + ";耗时 " + (System.currentTimeMillis() - t0) + "ms;"
                    + "正式执行请调 executeSql。");
        } catch (Exception e) {
            return McpToolResult.error("校验未通过: " + e.getMessage()
                    + "\n请按提示修正(仅单条 SELECT,只使用白名单表)后重试。");
        }
    }
}
