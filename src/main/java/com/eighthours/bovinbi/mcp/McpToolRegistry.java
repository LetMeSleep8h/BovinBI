package com.eighthours.bovinbi.mcp;

import com.eighthours.bovinbi.agent.AgentContextHolder;
import com.eighthours.bovinbi.agent.AgentRunContext;
import com.eighthours.bovinbi.agent.AgentTrace;
import com.eighthours.bovinbi.config.BovinProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MCP 工具统一注册中心(工具统一管理的单点):
 * - 本地工具(Spring 容器内全部 McpTool Bean)与远程 MCP server 的工具
 *   在此合并为一份目录:listTools() 给模型/外部客户端看,call() 是唯一执行入口;
 * - 调用横切面:预算扣减(仅 Agent 循环内,AgentRunContext.tryConsume)、
 *   轨迹记录(成功与失败都记)、异常隔离(工具抛异常转 isError 结果,绝不冒泡断循环);
 * - 远程 server 懒加载 + 失败隔离:任一 server 不可用只影响它自己的工具,
 *   其余目录照常;远程工具在循环内同样吃总预算。
 */
@Slf4j
@Component
public class McpToolRegistry {

    private final Map<String, McpTool> localTools = new LinkedHashMap<>();
    private final Map<String, McpToolDefinition> catalog = new ConcurrentHashMap<>();   // 全量目录(含远程)
    private final Map<String, McpRemoteServer> remoteOwners = new ConcurrentHashMap<>(); // 前缀名 → server
    private final BovinProperties props;
    private final ObjectMapper objectMapper;
    private volatile boolean remotesLoaded = false;

    public McpToolRegistry(List<McpTool> tools, BovinProperties props, ObjectMapper objectMapper) {
        tools.forEach(t -> localTools.put(t.definition().name(), t));
        localTools.forEach((name, t) -> catalog.put(name, t.definition()));
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** 统一工具目录:本地 + 远程(远程懒加载,首次调用时拉取) */
    public List<McpToolDefinition> listTools() {
        ensureRemotes();
        return new ArrayList<>(catalog.values());
    }

    /** 唯一执行入口:预算(循环内)→ 分发 → 轨迹/异常隔离 */
    public McpToolResult call(String name, Map<String, Object> arguments) {
        McpTool local = localTools.get(name);
        if (local != null) {
            return callLocal(local, arguments);
        }
        ensureRemotes();
        McpRemoteServer server = remoteOwners.get(name);
        if (server != null) {
            AgentRunContext ctx = AgentContextHolder.get();
            if (ctx != null) {
                String refusal = ctx.tryConsume(name);
                if (refusal != null) {
                    return McpToolResult.error(refusal);
                }
            }
            long t0 = System.currentTimeMillis();
            McpToolResult r = server.callTool(name.split("__", 2)[1], arguments);
            record(ctx, name, arguments, r, t0);
            return r;
        }
        return McpToolResult.error("未知工具: " + name + "(可用工具: " + catalog.keySet() + ")");
    }

    private McpToolResult callLocal(McpTool tool, Map<String, Object> arguments) {
        AgentRunContext ctx = AgentContextHolder.get();
        if (ctx != null) {
            // 预算在注册中心强制(而非各工具自查):远程/本地同权,超限返回话术让模型自行收尾
            String refusal = ctx.tryConsume(tool.definition().name());
            if (refusal != null) {
                return McpToolResult.error(refusal);
            }
        }
        long t0 = System.currentTimeMillis();
        try {
            McpToolResult r = tool.execute(arguments);
            record(ctx, tool.definition().name(), arguments, r, t0);
            return r;
        } catch (Exception e) {
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            String msg = root.getMessage() == null ? root.getClass().getSimpleName() : root.getMessage();
            record(ctx, tool.definition().name(), arguments, McpToolResult.error(msg), t0);
            return McpToolResult.error("工具执行失败: " + msg);
        }
    }

    private void record(AgentRunContext ctx, String name, Map<String, Object> args, McpToolResult r, long t0) {
        if (ctx != null) {
            ctx.record(new AgentTrace.ToolCall(ctx.nextSeq(), name, brief(argsToString(args)), !r.isError(),
                    (int) (System.currentTimeMillis() - t0), brief(r.text())));
        }
    }

    /** 懒加载远程 server 工具(一次性,失败只丢该 server);配置变更重启生效 */
    private synchronized void ensureRemotes() {
        if (remotesLoaded) {
            return;
        }
        remotesLoaded = true;
        for (BovinProperties.Mcp.RemoteServer cfg : props.getMcp().getServers()) {
            if (!cfg.isEnabled() || cfg.getUrl() == null || cfg.getUrl().isBlank()) {
                continue;
            }
            try {
                McpRemoteServer server = new McpRemoteServer(cfg.getName(), cfg.getUrl(), cfg.getApiKey(), objectMapper);
                for (McpToolDefinition def : server.listTools()) {
                    String prefixed = cfg.getName() + "__" + def.name();
                    catalog.put(prefixed, new McpToolDefinition(prefixed,
                            "[remote:" + cfg.getName() + "] " + def.description(), def.inputSchema()));
                    remoteOwners.put(prefixed, server);
                }
            } catch (Exception e) {
                log.warn("MCP 远程 server [{}] 不可用,已跳过其工具: {}", cfg.getName(), e.getMessage());
            }
        }
    }

    private String argsToString(Map<String, Object> args) {
        if (args == null || args.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        args.forEach((k, v) -> {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(k).append('=').append(v);
        });
        return sb.toString();
    }

    private static String brief(String s) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
