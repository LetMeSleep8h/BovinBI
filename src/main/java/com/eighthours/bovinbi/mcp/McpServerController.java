package com.eighthours.bovinbi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.Set;

/**
 * MCP server 端点(bovin.mcp.server-enabled=true 时暴露 POST /mcp):
 * 把 BovinBI 的工具集以 MCP 协议(JSON-RPC 2.0 over HTTP,无状态子集)发布出去,
 * Claude Desktop / 其他 Agent 平台可直接把本系统当工具源接入。
 * 支持 initialize / notifications/* / ping / tools/list / tools/call;
 * 鉴权:bovin.mcp.api-keys 非空时要求 Authorization: Bearer <key>(留空=演示不鉴权)。
 * 安全说明:tools/call 走 McpToolRegistry 同一入口,守护/白名单/参数校验与
 * 内部 Agent 循环完全同一套 —— 对外发布不放大攻击面。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
@ConditionalOnProperty(name = "bovin.mcp.server-enabled", havingValue = "true")
public class McpServerController {

    private final McpToolRegistry registry;
    private final ObjectMapper om;
    private final com.eighthours.bovinbi.config.BovinProperties props;

    @PostMapping(path = "/mcp", consumes = "application/json", produces = "application/json")
    public ResponseEntity<JsonNode> handle(@RequestBody JsonNode body,
                                           @RequestHeader(value = "Authorization", required = false) String auth) {
        Set<String> keys = props.getMcp().getApiKeys();
        if (!keys.isEmpty() && (auth == null || !keys.contains(auth.replaceFirst("(?i)^Bearer\\s+", "").trim()))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(error(null, -32001, "未授权:缺少或错误的 Bearer key"));
        }
        JsonNode id = body.path("id");
        String method = body.path("method").asText(null);
        if (method == null) {
            return ok(error(id, -32600, "无效请求:缺少 method"));
        }
        // 通知(id 为空):接受即静默,无响应体
        if (id.isMissingNode() || id.isNull()) {
            return ResponseEntity.noContent().build();
        }
        try {
            ObjectNode result = switch (method) {
                case "initialize" -> initialize();
                case "ping" -> om.createObjectNode();
                case "tools/list" -> toolsList();
                case "tools/call" -> toolsCall(body.path("params"));
                default -> null;
            };
            if (result == null) {
                return ok(error(id, -32601, "方法不存在: " + method));
            }
            ObjectNode resp = om.createObjectNode();
            resp.put("jsonrpc", "2.0");
            resp.set("id", id);
            resp.set("result", result);
            return ResponseEntity.ok(resp);
        } catch (Exception e) {
            log.warn("MCP 请求处理失败: {}", e.getMessage());
            return ok(error(id, -32603, "内部错误: " + e.getMessage()));
        }
    }

    private ObjectNode initialize() {
        ObjectNode result = om.createObjectNode();
        result.put("protocolVersion", "2024-11-05");
        ObjectNode caps = result.putObject("capabilities");
        caps.putObject("tools");
        ObjectNode info = result.putObject("serverInfo");
        info.put("name", "bovinbi-mcp");
        info.put("version", "1.0.0");
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = om.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        registry.listTools().forEach(def -> {
            ObjectNode t = tools.addObject();
            t.put("name", def.name());
            t.put("description", def.description());
            t.set("inputSchema", om.valueToTree(def.inputSchema()));
        });
        return result;
    }

    /** tools/call:结果按 MCP 形态回包(isError + content),与本地调用同一注册中心入口 */
    private ObjectNode toolsCall(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("tools/call 缺少工具名(name)");
        }
        Map<String, Object> arguments = om.convertValue(params.path("arguments"), Map.class);
        McpToolResult r = registry.call(name, arguments == null ? Map.of() : arguments);

        ObjectNode result = om.createObjectNode();
        result.put("isError", r.isError());
        ArrayNode content = result.putArray("content");
        r.content().forEach(c -> {
            ObjectNode item = content.addObject();
            item.put("type", c.type());
            item.put("text", c.text());
        });
        return result;
    }

    private JsonNode error(JsonNode id, int code, String message) {
        ObjectNode resp = om.createObjectNode();
        resp.put("jsonrpc", "2.0");
        if (id != null) {
            resp.set("id", id);
        }
        ObjectNode err = resp.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return resp;
    }

    private ResponseEntity<JsonNode> ok(JsonNode body) {
        return ResponseEntity.ok(body);
    }
}
