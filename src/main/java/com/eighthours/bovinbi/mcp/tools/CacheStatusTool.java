package com.eighthours.bovinbi.mcp.tools;

import com.eighthours.bovinbi.mcp.McpTool;
import com.eighthours.bovinbi.mcp.McpToolDefinition;
import com.eighthours.bovinbi.mcp.McpToolResult;
import com.eighthours.bovinbi.service.SemanticCache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * cacheStatus 工具:语义缓存与数据集的运行时体检(只读)。
 * 场景:外部 MCP 客户端/运维在执行前判断"这问题是不是刚问过"(命中即免执行),
 * 或排查"为什么秒回"——缓存命中率/请求量一目了然;含各数据集的表白名单速查。
 * 不消耗预算,不触及任何数据行。
 */
@Component
@RequiredArgsConstructor
public class CacheStatusTool implements McpTool {

    private final SemanticCache semanticCache;

    @Override
    public McpToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", McpToolDefinition.property("string", "stats=缓存统计(默认) / 无其它写操作——本工具只读"));
        return McpToolDefinition.of("cacheStatus",
                "查询语义缓存与运行状态(只读):命中率/请求数/条目规模。"
                        + "适合:判断问题是否刚被问过、排查秒级返回的原因。",
                props, java.util.List.of());
    }

    @Override
    public McpToolResult execute(Map<String, Object> arguments) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("requests", semanticCache.requestCount());
        m.put("hits", semanticCache.hitCount());
        double rate = semanticCache.requestCount() == 0 ? 0.0
                : semanticCache.hitCount() * 100.0 / semanticCache.requestCount();
        m.put("hitRate", String.format("%.1f%%", rate));
        return McpToolResult.text("语义缓存: 请求 " + semanticCache.requestCount()
                + " 次,命中 " + semanticCache.hitCount() + " 次,命中率 " + String.format("%.1f%%", rate)
                + "。命中即整段免 LLM/免查库;同问题换人再问同样秒回(缓存跨引擎/跨用户共享)。");
    }
}
