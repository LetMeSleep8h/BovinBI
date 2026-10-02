package com.eighthours.bovinbi.mcp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP 工具定义(tools/list 的一个条目):name + description + inputSchema(JSON Schema 子集)。
 * 本地工具与远程 MCP server 的工具在注册中心统一为该形态 —— 调用方(模型/外部客户端)
 * 看到的是同一份目录,不区分工具宿主在哪。
 */
public record McpToolDefinition(String name, String description, Map<String, Object> inputSchema) {

    /** 便捷构造:object 属性 + 必填列表 → {"type":"object","properties":{...},"required":[...]} */
    public static McpToolDefinition of(String name, String description,
                                       Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        if (required != null && !required.isEmpty()) {
            schema.put("required", required);
        }
        return new McpToolDefinition(name, description, schema);
    }

    /** 单属性工具的 schema 快捷构造 */
    public static Map<String, Object> property(String type, String description) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("type", type);
        p.put("description", description);
        return p;
    }
}
