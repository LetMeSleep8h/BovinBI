package com.eighthours.bovinbi.gateway;

/**
 * 上游供应商转发抽象:网关核心逻辑(路由/熔断/缓存)依赖本接口,单测可打桩;
 * 生产实现 {@link HttpProviderClient} 走 OpenAI 兼容 HTTP。
 */
public interface ProviderClient {

    record ForwardResult(String body, Integer promptTokens, Integer completionTokens) {
    }

    /** 转发一次 chat/completions;非 2xx 或网络异常抛出,由网关计失败并尝试下一家 */
    ForwardResult forward(BovinProvider provider, String jsonBody, int timeoutSeconds);

    /** 网关侧的供应商视图(与配置解耦,便于单测构造) */
    record BovinProvider(String name, String baseUrl, String apiKey, String model) {
    }
}
