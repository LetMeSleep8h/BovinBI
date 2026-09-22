package com.eighthours.bovinbi.controller;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.gateway.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 轻量 LLM 网关入口(OpenAI 兼容协议):POST /gateway/v1/chat/completions。
 * 接入方式 = 业务方把 bovin.llm.base-url 指向本端点,代码零改动;
 * 网关背后完成 限流 → 响应缓存 → 按优先级路由 → 熔断跳过 → failover → 计量。
 * 响应头 X-Bovin-Provider 透出实际服务的供应商,X-Bovin-Cache 标记缓存命中(可观测性)。
 */
@Slf4j
@RestController
@RequestMapping("/gateway/v1")
@RequiredArgsConstructor
public class GatewayController {

    private final LlmGateway llmGateway;
    private final BovinProperties props;

    @PostMapping(value = "/chat/completions", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> chatCompletions(
            @RequestBody String body,
            @RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        if (!props.getGateway().isEnabled()) {
            return ResponseEntity.status(503).body(
                    "{\"error\":{\"message\":\"网关未启用(bovin.gateway.enabled=false)\"}}");
        }
        String caller = authorization != null && authorization.startsWith("Bearer ")
                ? authorization.substring(7).trim() : "anonymous";
        LlmGateway.GatewayResult r = llmGateway.complete(new LlmGateway.GatewayRequest(caller, body));
        return ResponseEntity.status(r.status())
                .header("X-Bovin-Provider", r.provider() == null ? "none" : r.provider())
                .header("X-Bovin-Cache", String.valueOf(r.cached()))
                .body(r.body());
    }
}
