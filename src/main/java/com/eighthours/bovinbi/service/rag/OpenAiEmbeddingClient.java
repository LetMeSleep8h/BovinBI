package com.eighthours.bovinbi.service.rag;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

/**
 * OpenAI 兼容 /embeddings 端点实现(生产形态):DeepSeek/通义/GLM/OpenAI 通用,换供应商只改配置。
 * 失败直接抛出 —— 调用方(HybridSchemaRetriever/SemanticCache)负责降级回词面/精确命中,
 * 保证 LLM 不可用时系统仍可服务(RAG 是增强,不是依赖)。
 */
@Slf4j
public class OpenAiEmbeddingClient implements EmbeddingClient {

    private final BovinProperties.Rag.Embedding cfg;
    private final ObjectMapper objectMapper;
    private final HttpClient http;

    public OpenAiEmbeddingClient(BovinProperties.Rag.Embedding cfg, ObjectMapper objectMapper) {
        this.cfg = cfg;
        this.objectMapper = objectMapper;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    }

    @Override
    public float[] embed(String text) {
        try {
            String url = cfg.getBaseUrl().replaceAll("/+$", "") + "/embeddings";
            String body = objectMapper.writeValueAsString(List.of(text));
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + cfg.getApiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(
                            "{\"model\":\"" + cfg.getModel() + "\",\"input\":" + body + "}"))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new BizException(502, "Embedding 端点返回 " + resp.statusCode() + ": " + resp.body());
            }
            JsonNode arr = objectMapper.readTree(resp.body()).path("data").path(0).path("embedding");
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) v[i] = (float) arr.get(i).asDouble();
            double norm = 0.0;
            for (float x : v) norm += (double) x * x;
            norm = Math.sqrt(norm);
            if (norm > 0) {
                for (int i = 0; i < v.length; i++) v[i] /= (float) norm;
            }
            return v;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Embedding 调用失败: {}", e.getMessage());
            throw new BizException(502, "Embedding 调用失败: " + e.getMessage());
        }
    }
}
