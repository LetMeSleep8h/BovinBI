package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Python Agent 客户端(engine=python 的提问路由):
 * - 把问题转发给独立的 Python 服务(python-agent/,FastAPI),拿回与
 *   AnswerPayload 同构的结果(含 steps 轨迹)—— 前端 AnswerCard 直接渲染;
 * - Python 侧的数据操作全部经 MCP 回环到本服务 /mcp,守护/白名单不外流;
 * - 超时/不可达抛 BizException,由 ChatService 降级 Java 引擎,演示不中断。
 */
@Slf4j
@Service
public class PythonAgentService {

    private final BovinProperties props;
    private final ObjectMapper objectMapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public PythonAgentService(BovinProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper.copy()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** 转发提问并映射为 AnswerPayload(engine 固定为 PYTHON,steps 转 trace) */
    public AnswerPayload answer(Long datasetId, String question, Long sessionId, String model) {
        var cfg = props.getPythonAgent();
        try {
            java.util.Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("datasetId", datasetId);
            payload.put("question", question);
            payload.put("sessionId", sessionId == null ? 0 : sessionId);
            if (model != null && !model.isBlank()) {
                payload.put("model", model.trim());
            }
            String body = objectMapper.writeValueAsString(payload);
            HttpRequest req = HttpRequest.newBuilder(URI.create(cfg.getUrl() + "/v1/answer"))
                    .timeout(Duration.ofSeconds(cfg.getTimeoutSeconds()))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("Python Agent HTTP " + resp.statusCode() + ": " + snippet(resp.body()));
            }
            return toPayload(objectMapper.readTree(resp.body()));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Python Agent 调用失败: {}", e.getMessage());
            throw new BizException(502, "Python Agent 不可用: " + e.getMessage());
        }
    }

    /** Python 返回的 JSON → AnswerPayload(sql/列/行/回退标记 + steps→trace) */
    private AnswerPayload toPayload(JsonNode json) {
        AnswerPayload p = new AnswerPayload();
        p.setSql(json.path("sql").asText(null));
        p.setExplanation(json.path("explanation").asText(null));
        p.setColumns(objectMapper.convertValue(json.path("columns"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, com.eighthours.bovinbi.dto.ColInfo.class)));
        p.setRows(objectMapper.convertValue(json.path("rows"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, java.util.LinkedHashMap.class)));
        p.setRowCount(json.path("rowCount").asInt(0));
        p.setFallback(json.path("fallback").asBoolean(false));
        p.setFallbackHint(json.path("fallbackHint").asText(null));
        p.setEngine("PYTHON");
        p.setTookMs(json.path("tookMs").asInt(0));
        // steps(Python 侧轨迹)→ AgentTrace,实时流上已推过,这里随载荷返回完整版
        List<com.eighthours.bovinbi.agent.AgentTrace.ToolCall> trace = new ArrayList<>();
        int seq = 0;
        for (JsonNode s : json.path("steps")) {
            trace.add(new com.eighthours.bovinbi.agent.AgentTrace.ToolCall(++seq,
                    s.path("name").asText("step"), s.path("detail").asText(""), s.path("ok").asBoolean(true),
                    0, "python"));
        }
        p.setTrace(trace);
        return p;
    }

    private String snippet(String s) {
        if (s == null) {
            return "";
        }
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 200 ? one : one.substring(0, 200);
    }
}
