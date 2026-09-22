package com.eighthours.bovinbi.gateway;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.entity.GatewayUsage;
import com.eighthours.bovinbi.gateway.ProviderClient.BovinProvider;
import com.eighthours.bovinbi.gateway.ProviderClient.ForwardResult;
import com.eighthours.bovinbi.mapper.GatewayUsageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 网关核心策略链契约(failover/熔断/限流/缓存/计量),上游 HTTP 用桩隔离:
 * 1) failover:主供应商失败自动切备,计量两次(失败+成功都落);
 * 2) 熔断:连续失败达阈值后跳过该供应商,不再发起转发;
 * 3) 限流:窗口内超 RPM 返回 429;
 * 4) 响应缓存:同请求体 TTL 内回放,不再转发。
 */
class LlmGatewayImplTest {

    private BovinProperties props;
    private ProviderClient providerClient;
    private GatewayUsageMapper usageMapper;
    private LlmGatewayImpl gateway;

    private static final String BODY = "{\"model\":\"x\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    @BeforeEach
    void setUp() {
        props = new BovinProperties();
        providerClient = mock(ProviderClient.class);
        usageMapper = mock(GatewayUsageMapper.class);
        gateway = new LlmGatewayImpl(props, providerClient, usageMapper);
    }

    private BovinProperties.Gateway.Provider provider(String name, int priority) {
        BovinProperties.Gateway.Provider p = new BovinProperties.Gateway.Provider();
        p.setName(name);
        p.setBaseUrl("http://" + name);
        p.setApiKey("sk-" + name);
        p.setModel("m-" + name);
        p.setPriority(priority);
        return p;
    }

    @Test
    void failsOverToSecondProviderAndMetersBoth() {
        props.getGateway().setProviders(List.of(provider("primary", 1), provider("backup", 2)));
        when(providerClient.forward(any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("primary down"))
                .thenReturn(new ForwardResult("{\"ok\":true}", 10, 5));

        LlmGateway.GatewayResult r = gateway.complete(new LlmGateway.GatewayRequest("k1", BODY));

        assertEquals(200, r.status());
        assertEquals("backup", r.provider());
        assertFalse(r.cached());
        // 计量:主供应商失败一行 + 备份成功一行
        verify(usageMapper, times(2)).insert(any(GatewayUsage.class));
    }

    @Test
    void cacheReplaysSameBodyWithoutForwarding() {
        props.getGateway().setProviders(List.of(provider("only", 1)));
        when(providerClient.forward(any(), anyString(), anyInt()))
                .thenReturn(new ForwardResult("{\"ok\":1}", null, null));

        gateway.complete(new LlmGateway.GatewayRequest("k1", BODY));
        LlmGateway.GatewayResult second = gateway.complete(new LlmGateway.GatewayRequest("k1", BODY));

        assertTrue(second.cached());
        assertEquals("only", second.provider());
        verify(providerClient, times(1)).forward(any(), anyString(), anyInt());
    }

    @Test
    void breakerOpensAfterConsecutiveFailures() {
        props.getGateway().setProviders(List.of(provider("sick", 1)));
        props.getGateway().setBreakerThreshold(2);
        when(providerClient.forward(any(), anyString(), anyInt()))
                .thenThrow(new IllegalStateException("always down"));

        assertEquals(502, gateway.complete(new LlmGateway.GatewayRequest("k", BODY)).status());
        assertEquals(502, gateway.complete(new LlmGateway.GatewayRequest("k", BODY)).status());
        // 第 3 次:熔断已打开,不再发起转发
        LlmGateway.GatewayResult third = gateway.complete(new LlmGateway.GatewayRequest("k", BODY));
        assertEquals(502, third.status());
        verify(providerClient, times(2)).forward(any(), anyString(), anyInt());
    }

    @Test
    void rateLimitReturns429BeyondRpm() {
        props.getGateway().setApiKeys(new java.util.LinkedHashMap<>(java.util.Map.of("k1", 2)));
        props.getGateway().setProviders(List.of(provider("p", 1)));
        when(providerClient.forward(any(), anyString(), anyInt()))
                .thenReturn(new ForwardResult("{\"ok\":1}", null, null));

        assertEquals(200, gateway.complete(new LlmGateway.GatewayRequest("k1", BODY + "1")).status());
        assertEquals(200, gateway.complete(new LlmGateway.GatewayRequest("k1", BODY + "2")).status());
        LlmGateway.GatewayResult third = gateway.complete(new LlmGateway.GatewayRequest("k1", BODY + "3"));
        assertEquals(429, third.status());
        // 超限请求不触达供应商
        verify(providerClient, times(2)).forward(any(), anyString(), anyInt());
    }

    @Test
    void unknownApiKeyRejected() {
        props.getGateway().setApiKeys(new java.util.LinkedHashMap<>(java.util.Map.of("known", 60)));
        props.getGateway().setProviders(List.of(provider("p", 1)));
        LlmGateway.GatewayResult r = gateway.complete(new LlmGateway.GatewayRequest("intruder", BODY));
        assertEquals(429, r.status());
        assertTrue(r.body().contains("无效的 API Key"));
    }
}
