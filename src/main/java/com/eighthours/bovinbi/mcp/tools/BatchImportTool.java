package com.eighthours.bovinbi.mcp.tools;

import com.eighthours.bovinbi.agent.AgentContextHolder;
import com.eighthours.bovinbi.mcp.McpTool;
import com.eighthours.bovinbi.mcp.McpToolDefinition;
import com.eighthours.bovinbi.mcp.McpToolResult;
import com.eighthours.bovinbi.service.DataPortService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.StringReader;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * batchImportData 工具:CSV 文本批量导入白名单表(HTTP 端点之外的第二个入口,面向 MCP 客户端;
 * 鉴权由 /mcp 的 api-keys 把守)。三道闸与 DataPortService.importCsv 一致:
 * 表白名单 / 列必须是已配置业务字段 / 标识符校验;值走参数绑定,无注入面。
 */
@Component
@RequiredArgsConstructor
public class BatchImportTool implements McpTool {

    private final DataPortService dataPortService;
    private final ToolSupport support;

    @Override
    public McpToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("table", McpToolDefinition.property("string", "目标表名,必须在数据集白名单内"));
        props.put("csv", McpToolDefinition.property("string", "CSV 文本,首行为列名(须是该表已配置的业务字段)"));
        props.put("datasetId", McpToolDefinition.property("integer", "数据集 id;Agent 循环内可省略"));
        return McpToolDefinition.of("batchImportData",
                "把 CSV 文本批量导入指定表(首行列名)。表必须在数据集白名单内、列必须是已配置业务字段;"
                        + "值以参数绑定写入。返回插入行数。",
                props, java.util.List.of("table", "csv"));
    }

    @Override
    public McpToolResult execute(Map<String, Object> arguments) {
        String table = ToolSupport.str(arguments, "table");
        String csv = ToolSupport.str(arguments, "csv");
        if (table == null || csv == null || csv.isBlank()) {
            return McpToolResult.error("参数非法:table 与 csv 均不能为空");
        }
        Long datasetId = support.resolveDatasetId(arguments, AgentContextHolder.get());
        if (datasetId == null) {
            return McpToolResult.error("缺少 datasetId 参数:Agent 循环外调用(如 MCP 客户端)必须显式指定数据集");
        }
        try {
            int rows = dataPortService.importCsv(datasetId, table, new StringReader(csv));
            return McpToolResult.text("导入成功: " + rows + " 行写入 " + table);
        } catch (Exception e) {
            return McpToolResult.error("导入失败: " + e.getMessage());
        }
    }
}
