package com.eighthours.bovinbi.mcp;

import com.eighthours.bovinbi.config.BovinProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MCP server 端点契约(直接实例化控制器,JSON-RPC 协议级验证):
 * initialize 握手 → tools/list 目录 → tools/call 调用全链路;
 * 未知方法 -32601;鉴权开启后缺 key 401。
 */
class McpServerControllerTest {

    private final ObjectMapper om = new ObjectMapper();
    private BovinProperties props;
    private McpServerController controller;

    @BeforeEach
    void setUp() {
        props = new BovinProperties();
        McpTool echo = new McpTool() {
            @Override
            public McpToolDefinition definition() {
                return McpToolDefinition.of("echo", "回显工具", Map.of("msg", Map.of("type", "string")), List.of("msg"));
            }

            @Override
            public McpToolResult execute(Map<String, Object> arguments) {
                return McpToolResult.text("echo:" + arguments.get("msg"));
            }
        };
        controller = new McpServerController(new McpToolRegistry(List.of(echo), props, om), om, props);
    }

    private JsonNode rpc(String method, JsonNode params, String auth) throws Exception {
        String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"" + method + "\""
                + (params == null ? "" : ",\"params\":" + om.writeValueAsString(params)) + "}";
        ResponseEntity<JsonNode> resp = controller.handle(om.readTree(body), auth);
        assertEquals(200, resp.getStatusCode().value());
        return resp.getBody();
    }

    @Test
    void initializeHandshake() throws Exception {
        JsonNode r = rpc("initialize", null, null);
        assertEquals("2024-11-05", r.path("result").path("protocolVersion").asText());
        assertEquals("bovinbi-mcp", r.path("result").path("serverInfo").path("name").asText());
    }

    @Test
    void toolsListExposesUnifiedCatalog() throws Exception {
        JsonNode r = rpc("tools/list", null, null);
        assertEquals("echo", r.path("result").path("tools").get(0).path("name").asText());
        assertEquals("object", r.path("result").path("tools").get(0).path("inputSchema").path("type").asText());
    }

    @Test
    void toolsCallReturnsMcpShapedResult() throws Exception {
        JsonNode r = rpc("tools/call",
                om.readTree("{\"name\":\"echo\",\"arguments\":{\"msg\":\"hi\"}}"), null);
        assertFalse(r.path("result").path("isError").asBoolean());
        assertEquals("echo:hi", r.path("result").path("content").get(0).path("text").asText());
    }

    @Test
    void unknownMethodRejectedWithProtocolError() throws Exception {
        JsonNode r = rpc("resources/list", null, null);
        assertEquals(-32601, r.path("error").path("code").asInt());
    }

    @Test
    void apiKeyRequiredWhenConfigured() throws Exception {
        props.getMcp().getApiKeys().add("secret-key");
        String body = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/list\"}";
        ResponseEntity<JsonNode> no = controller.handle(om.readTree(body), null);
        assertEquals(401, no.getStatusCode().value());

        ResponseEntity<JsonNode> ok = controller.handle(om.readTree(body), "Bearer secret-key");
        assertEquals(200, ok.getStatusCode().value());
        assertTrue(ok.getBody().path("result").path("tools").size() > 0);
    }
}
