package com.eighthours.bovinbi.gateway;

/**
 * LLM 网关接口:统一 OpenAI 兼容入口背后的路由/failover/熔断/限流/计量/响应缓存。
 * 职责边界:HTTP 协议细节在 {@link HttpProviderClient},编排策略在本接口的实现里。
 */
public interface LlmGateway {

    /** 网关调用请求:调用方 key + 原始 OpenAI 请求体 */
    record GatewayRequest(String caller, String jsonBody) {
    }

    /** 网关调用结果:HTTP 状态 + 响应体 + 实际服务的供应商 + 是否缓存命中 */
    record GatewayResult(int status, String body, String provider, boolean cached) {
    }

    GatewayResult complete(GatewayRequest request);
}
