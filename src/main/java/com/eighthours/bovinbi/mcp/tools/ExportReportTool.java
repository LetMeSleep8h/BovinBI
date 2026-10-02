package com.eighthours.bovinbi.mcp.tools;

import com.eighthours.bovinbi.agent.AgentContextHolder;
import com.eighthours.bovinbi.mcp.McpTool;
import com.eighthours.bovinbi.mcp.McpToolDefinition;
import com.eighthours.bovinbi.mcp.McpToolResult;
import com.eighthours.bovinbi.service.DataPortService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * exportReport 工具:把查询结果导出成 CSV(文本形态返回,MCP 客户端/Agent 可直接消费)。
 * 与 HTTP 下载端点同一 DataPortService:SQL 过守护(表白名单/强制 LIMIT)后才执行 ——
 * 导出不放宽任何查询权限。LIMIT 上限即导出行数上限,天然防超大结果打爆上下文。
 */
@Component
@RequiredArgsConstructor
public class ExportReportTool implements McpTool {

    private final DataPortService dataPortService;
    private final ToolSupport support;

    @Override
    public McpToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sql", McpToolDefinition.property("string", "只读 SELECT,表和列必须来自 Schema"));
        props.put("datasetId", McpToolDefinition.property("integer", "数据集 id;Agent 循环内可省略"));
        return McpToolDefinition.of("exportReport",
                "把一条查询的结果导出为 CSV 报表文本(首行列名)。SQL 会经过安全守护"
                        + "(仅单条 SELECT/表白名单/强制 LIMIT),导出权限与查询权限一致。",
                props, java.util.List.of("sql"));
    }

    @Override
    public McpToolResult execute(Map<String, Object> arguments) {
        String sql = ToolSupport.str(arguments, "sql");
        if (sql == null || sql.isBlank()) {
            return McpToolResult.error("参数非法:sql 不能为空");
        }
        Long datasetId = support.resolveDatasetId(arguments, AgentContextHolder.get());
        if (datasetId == null) {
            return McpToolResult.error("缺少 datasetId 参数:Agent 循环外调用(如 MCP 客户端)必须显式指定数据集");
        }
        try {
            byte[] csv = dataPortService.exportCsv(datasetId, sql);
            return McpToolResult.text(new String(csv, StandardCharsets.UTF_8));
        } catch (Exception e) {
            return McpToolResult.error("导出失败: " + e.getMessage());
        }
    }
}
