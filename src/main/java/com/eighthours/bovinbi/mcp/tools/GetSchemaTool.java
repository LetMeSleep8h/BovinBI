package com.eighthours.bovinbi.mcp.tools;

import com.eighthours.bovinbi.agent.AgentContextHolder;
import com.eighthours.bovinbi.agent.AgentRunContext;
import com.eighthours.bovinbi.mcp.McpTool;
import com.eighthours.bovinbi.mcp.McpToolDefinition;
import com.eighthours.bovinbi.mcp.McpToolResult;
import com.eighthours.bovinbi.service.SchemaRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * getSchema 工具:Schema 召回并把表白名单登记进运行上下文(循环内形态)。
 * 预算扣减与轨迹由 McpToolRegistry 统一处理,工具只管业务与安全。
 */
@Component
@RequiredArgsConstructor
public class GetSchemaTool implements McpTool {

    private final SchemaRetriever schemaRetriever;
    private final ToolSupport support;

    @Override
    public McpToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("keywords", McpToolDefinition.property("string", "问题关键词,用于字段召回排序;可空"));
        props.put("datasetId", McpToolDefinition.property("integer", "数据集 id;Agent 循环内可省略"));
        return McpToolDefinition.of("getSchema",
                "获取数据集的表结构与业务字段说明(字段业务名/口径/聚合方式/同义词)。"
                        + "写任何 SQL 之前必须先调用本工具;可传入问题关键词提高字段召回排序。",
                props, null);
    }

    @Override
    public McpToolResult execute(Map<String, Object> arguments) {
        AgentRunContext ctx = AgentContextHolder.get();
        Long datasetId = support.resolveDatasetId(arguments, ctx);
        if (datasetId == null) {
            return McpToolResult.error("缺少 datasetId 参数:Agent 循环外调用(如 MCP 客户端)必须显式指定数据集");
        }
        String keywords = ToolSupport.str(arguments, "keywords");
        String question = keywords != null && !keywords.isBlank() ? keywords
                : (ctx == null ? "" : ctx.question());
        SchemaRetriever.LinkedSchema linked = schemaRetriever.retrieve(datasetId, question);
        if (ctx != null) {
            ctx.whitelist(linked.whitelist()); // 后续 executeSql/getColumnValues 的守护依据
        }
        return McpToolResult.text(linked.schemaText());
    }
}
