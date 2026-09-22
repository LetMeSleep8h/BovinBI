package com.eighthours.bovinbi.gateway;

import com.eighthours.bovinbi.gateway.ProviderClient.BovinProvider;
import com.eighthours.bovinbi.gateway.ProviderClient.ForwardResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * OpenAI 兼容 HTTP 转发实现:POST {base-url}/chat/completions,失败(非2xx/网络异常)抛出由网关处理。
 * 转发时强制覆盖 model 为供应商配置值(请求体里的 model 仅作为业务方偏好,路由权在网关)。
 */
@Slf4j
@Component
public class HttpProviderClient implements ProviderClient {

    private final HttpClient http;
    private final ObjectMapper objectMapper;

    public HttpProviderClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public ForwardResult forward(BovinProvider provider, String jsonBody, int timeoutSeconds) {
        try {
            String url = provider.baseUrl().replaceAll("/+$", "") + "/chat/completions";
            String body = forceModel(jsonBody, provider.model());
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + provider.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
                throw new IllegalStateException("provider " + provider.name() + " 返回 "
                        + resp.statusCode() + ": " + snippet(resp.body()));
            }
            JsonNode usage = objectMapper.readTree(resp.body()).path("usage");
            return new ForwardResult(resp.body(),
                    usage.path("prompt_tokens").isInt() ? usage.path("prompt_tokens").asInt() : null,
                    usage.path("completion_tokens").isInt() ? usage.path("completion_tokens").asInt() : null);
        } catch (java.net.http.HttpTimeoutException e) {
            throw new IllegalStateException("provider " + provider.name() + " 超时(" + timeoutSeconds + "s)");
        } catch (Exception e) {
            if (e instanceof IllegalStateException ise) throw ise;
            throw new IllegalStateException("provider " + provider.name() + " 转发失败: " + e.getMessage());
        }
    }

    /** 请求体 model 字段替换为该供应商配置的模型(轻量字符串处理,避免解析-再序列化破坏其余字段) */
    private String forceModel(String jsonBody, String model) {
        if (model == null || model.isBlank()) return jsonBody;
        return jsonBody.replaceAll("\"model\"\\s*:\\s*\"[^\"]*\"", "\"model\":\"" + model + "\"");
    }

    private String snippet(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 200 ? one : one.substring(0, 200) + "…";
    }
}
