package com.eighthours.bovinbi.mcp.tools;

import com.eighthours.bovinbi.agent.AgentContextHolder;
import com.eighthours.bovinbi.agent.AgentRunContext;
import com.eighthours.bovinbi.mcp.McpTool;
import com.eighthours.bovinbi.mcp.McpToolDefinition;
import com.eighthours.bovinbi.mcp.McpToolResult;
import com.eighthours.bovinbi.service.SchemaRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * refreshSchema 工具:数据集元数据口径变更(ADMIN 改了字段别名/同义词)或
 * 刚批量导入数据后,显式刷新召回用的缓存与白名单。
 * 场景:演示中现场改字段口径→不刷新的话 Schema 召回仍用旧词典,模型拿到的
 * 字段业务名与库中实际口径脱节;此工具把"我知道数据变了"变成模型可表达的意图。
 */
@Component
@RequiredArgsConstructor
public class RefreshSchemaTool implements McpTool {

    private final ToolSupport support;
    private final SchemaRetriever schemaRetriever;

    @Override
    public McpToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("datasetId", McpToolDefinition.property("integer", "数据集 id;Agent 循环内可省略"));
        return McpToolDefinition.of("refreshSchema",
                "刷新数据集的 Schema 召回缓存与表白名单。适用:刚执行 batchImportData 导入新数据、"
                        + "或管理员修改了字段口径之后,再取 Schema/执行 SQL 前调用一次。",
                props, java.util.List.of());
    }

    @Override
    public McpToolResult execute(Map<String, Object> arguments) {
        AgentRunContext ctx = AgentContextHolder.get();
        Long datasetId = support.resolveDatasetId(arguments, ctx);
        if (datasetId == null) {
            return McpToolResult.error("缺少 datasetId 参数:Agent 循环外调用(如 MCP 客户端)必须显式指定数据集");
        }
        try {
            SchemaRetriever.LinkedSchema linked = schemaRetriever.retrieve(datasetId, "");
            if (ctx != null) {
                ctx.whitelist(linked.whitelist()); // 白名单立即以最新元数据为准
            }
            return McpToolResult.text("已刷新:表白名单=" + linked.whitelist()
                    + ";Schema 文本 " + linked.schemaText().length() + " 字符(下次 getSchema 取最新)");
        } catch (Exception e) {
            return McpToolResult.error("刷新失败: " + e.getMessage());
        }
    }
}
