package com.eighthours.bovinbi.mcp;

import com.eighthours.bovinbi.agent.AgentContextHolder;
import com.eighthours.bovinbi.agent.AgentRunContext;
import com.eighthours.bovinbi.config.BovinProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP 注册中心契约:
 * 1) 统一目录:本地工具 + 远程 server 工具(带 服务名__ 前缀)合并可见;
 * 2) 异常隔离:工具抛异常转 isError 结果,绝不冒泡断掉调用方;
 * 3) 预算强制(循环内):executeSql 超限返回拒绝话术且不记轨迹;
 * 4) 轨迹:成功/失败都记录进 AgentRunContext;
 * 5) 远程失败隔离:不可达 server 只丢自己的工具,本地工具照常可用。
 */
class McpToolRegistryTest {

    private HttpServer stub;
    private BovinProperties props;

    /** 最小可用的本地工具:name→echo 参数原样返回;throwIt=true 时抛异常 */
    private static McpTool echoTool(String name, boolean throwIt) {
        return new McpTool() {
            @Override
            public McpToolDefinition definition() {
                return McpToolDefinition.of(name, "测试工具", Map.of("msg", Map.of("type", "string")), List.of("msg"));
            }

            @Override
            public McpToolResult execute(Map<String, Object> arguments) {
                if (throwIt) {
                    throw new IllegalStateException("boom");
                }
                return McpToolResult.text(name + ":" + arguments.get("msg"));
            }
        };
    }

    @BeforeEach
    void setUp() throws IOException {
        props = new BovinProperties();
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            com.fasterxml.jackson.databind.JsonNode req = new ObjectMapper().readTree(body);
            String method = req.path("method").asText();
            String resp;
            if ("initialize".equals(method)) {
                resp = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":\"2024-11-05\"}}";
            } else if ("tools/list".equals(method)) {
                resp = "{\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"tools\":[{\"name\":\"echo\","
                        + "\"description\":\"远程回显\",\"inputSchema\":{\"type\":\"object\"}}]}}";
            } else if ("tools/call".equals(method)) {
                resp = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"text\":\"remote-echo\"}],\"isError\":false}}";
            } else {
                resp = "{\"jsonrpc\":\"2.0\",\"id\":9,\"error\":{\"code\":-32601,\"message\":\"no\"}}";
            }
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });
        stub.start();
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
        stub.stop(0);
    }

    private McpToolRegistry registryWithRemote(boolean remoteEnabled, String url, McpTool... locals) {
        if (remoteEnabled) {
            BovinProperties.Mcp.RemoteServer rs = new BovinProperties.Mcp.RemoteServer();
            rs.setName("stub");
            rs.setUrl(url);
            props.getMcp().getServers().add(rs);
        }
        return new McpToolRegistry(List.of(locals), props, new ObjectMapper());
    }

    @Test
    void catalogMergesLocalAndPrefixedRemoteTools() {
        McpToolRegistry registry = registryWithRemote(true, stubUrl(), echoTool("hello", false));
        List<String> names = registry.listTools().stream().map(McpToolDefinition::name).toList();
        assertTrue(names.contains("hello"), "本地工具应在目录中: " + names);
        assertTrue(names.contains("stub__echo"), "远程工具应带服务名前缀: " + names);
    }

    @Test
    void remoteCallRoundTripsThroughJsonRpc() {
        McpToolRegistry registry = registryWithRemote(true, stubUrl(), echoTool("hello", false));
        McpToolResult r = registry.call("stub__echo", Map.of("msg", "hi"));
        assertFalse(r.isError());
        assertEquals("remote-echo", r.text());
    }

    @Test
    void unreachableRemoteServerIsolatedFromCatalog() {
        McpToolRegistry registry = registryWithRemote(true, "http://127.0.0.1:1/mcp", echoTool("hello", false));
        List<String> names = registry.listTools().stream().map(McpToolDefinition::name).toList();
        assertTrue(names.contains("hello"), "远程不可达只丢远程工具,本地照常: " + names);
        assertFalse(names.contains("stub__echo"));
    }

    @Test
    void toolExceptionIsolatedAsErrorResult() {
        McpToolRegistry registry = registryWithRemote(false, null, echoTool("boomTool", true));
        McpToolResult r = registry.call("boomTool", Map.of("msg", "x"));
        assertTrue(r.isError(), "工具异常应转 isError 结果而非冒泡");
        assertTrue(r.text().contains("boom"));
    }

    @Test
    void unknownToolReturnsErrorWithAvailableList() {
        McpToolRegistry registry = registryWithRemote(false, null, echoTool("hello", false));
        McpToolResult r = registry.call("nope", Map.of());
        assertTrue(r.isError());
        assertTrue(r.text().contains("未知工具"));
        assertTrue(r.text().contains("hello"));
    }

    @Test
    void budgetEnforcedOnlyInsideAgentLoop() {
        McpToolRegistry registry = registryWithRemote(false, null, echoTool("executeSql", false));
        // 循环外:无预算,任意次可调
        for (int i = 0; i < 5; i++) {
            assertFalse(registry.call("executeSql", Map.of("msg", "x")).isError());
        }
        // 循环内:SQL 执行预算(默认 3)第 4 次拒绝
        AgentRunContext ctx = new AgentRunContext(1L, "q", props.getChat().getAgent());
        AgentContextHolder.set(ctx);
        for (int i = 0; i < 3; i++) {
            assertFalse(registry.call("executeSql", Map.of("msg", "x")).isError());
        }
        McpToolResult refusal = registry.call("executeSql", Map.of("msg", "x"));
        assertTrue(refusal.isError());
        assertTrue(refusal.text().contains("上限"), "超限应返回拒绝话术: " + refusal.text());
        assertTrue(refusal.text().contains("最终 JSON"));
        assertEquals(3, ctx.trace().stream().filter(t -> "executeSql".equals(t.tool())).count(),
                "成功 3 次各记一条轨迹,拒绝不记");
    }

    @Test
    void traceRecordsSuccessAndFailureInsideLoop() {
        McpToolRegistry registry = registryWithRemote(false, null, echoTool("okTool", false), echoTool("boomTool", true));
        AgentRunContext ctx = new AgentRunContext(1L, "q", props.getChat().getAgent());
        AgentContextHolder.set(ctx);
        registry.call("okTool", Map.of("msg", "x"));
        registry.call("boomTool", Map.of("msg", "x"));
        assertTrue(ctx.trace().stream().anyMatch(t -> "okTool".equals(t.tool()) && t.ok()));
        assertTrue(ctx.trace().stream().anyMatch(t -> "boomTool".equals(t.tool()) && !t.ok()));
    }

    private String stubUrl() {
        return "http://127.0.0.1:" + stub.getAddress().getPort() + "/mcp";
    }
}
