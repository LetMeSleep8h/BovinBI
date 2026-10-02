package com.eighthours.bovinbi.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 远程 MCP server 客户端(JSON-RPC 2.0 over HTTP,无状态子集):
 * - listTools:initialize(握手)→ tools/list,工具目录进入本地注册中心(带服务名前缀);
 * - callTool:tools/call 转发参数,回传 content/isError。
 * 远程工具与本地工具同一形态 —— 注册中心的调用方不需要知道工具宿主在哪。
 * 任何失败(超时/非 2xx/协议错误)抛 IllegalStateException,由注册中心决定隔离。
 */
@Slf4j
public class McpRemoteServer {

    private final String name;
    private final String url;
    private final String apiKey;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper om;
    private final AtomicLong ids = new AtomicLong(1);

    public McpRemoteServer(String name, String url, String apiKey, ObjectMapper om) {
        this.name = name;
        this.url = url;
        this.apiKey = apiKey;
        this.om = om;
    }

    public String name() {
        return name;
    }

    /** initialize + tools/list;失败抛异常(注册中心跳过该 server,不影响其余工具) */
    public List<McpToolDefinition> listTools() {
        try {
            rpc("initialize", Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "bovinbi-mcp-client", "version", "1.0.0")));
            JsonNode result = rpc("tools/list", Map.of());
            List<McpToolDefinition> out = new ArrayList<>();
            for (JsonNode t : result.path("tools")) {
                out.add(new McpToolDefinition(t.path("name").asText(),
                        t.path("description").asText(""),
                        om.convertValue(t.path("inputSchema").deepCopy(), Map.class)));
            }
            log.info("MCP 远程 server [{}] 就绪: {} 个工具", name, out.size());
            return out;
        } catch (Exception e) {
            throw new IllegalStateException("server " + name + " tools/list 失败: " + e.getMessage(), e);
        }
    }

    /** tools/call 转发;远程错误转 isError 结果而非异常(错误即数据,与本地工具同契约) */
    public McpToolResult callTool(String toolName, Map<String, Object> arguments) {
        try {
            JsonNode result = rpc("tools/call", Map.of("name", toolName,
                    "arguments", arguments == null ? Map.of() : arguments));
            List<McpToolResult.Content> contents = new ArrayList<>();
            for (JsonNode c : result.path("content")) {
                contents.add(McpToolResult.Content.text(c.path("text").asText("")));
            }
            if (contents.isEmpty()) {
                contents.add(McpToolResult.Content.text(om.writeValueAsString(result)));
            }
            return new McpToolResult(contents, result.path("isError").asBoolean(false));
        } catch (Exception e) {
            return McpToolResult.error("远程工具调用失败[" + name + "." + toolName + "]: " + e.getMessage());
        }
    }

    private JsonNode rpc(String method, Map<String, Object> params) throws Exception {
        ObjectNode req = om.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", ids.getAndIncrement());
        req.put("method", method);
        req.set("params", om.valueToTree(params));
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(om.writeValueAsString(req)));
        if (apiKey != null && !apiKey.isBlank()) {
            rb.header("Authorization", "Bearer " + apiKey);
        }
        HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + resp.statusCode());
        }
        JsonNode body = om.readTree(resp.body());
        // 兼容 streamable-http 的 SSE 包裹(data: {...});普通 JSON 直接过
        if (body.isMissingNode() || body.isNull()) {
            throw new IllegalStateException("响应体不是 JSON");
        }
        if (body.has("error")) {
            throw new IllegalStateException(body.path("error").path("message").asText("未知错误"));
        }
        return body.path("result");
    }
}
