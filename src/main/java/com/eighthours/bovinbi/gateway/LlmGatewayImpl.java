package com.eighthours.bovinbi.gateway;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.entity.GatewayUsage;
import com.eighthours.bovinbi.gateway.ProviderClient.BovinProvider;
import com.eighthours.bovinbi.gateway.ProviderClient.ForwardResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * LLM 网关实现:一次调用的完整策略链 = 限流 → 响应缓存 → 按优先级路由 → 熔断跳过 → failover → 计量。
 * 设计要点:
 * - 供应商选择按 priority 升序,失败自动 failover 到下一家;连续失败达阈值熔断 N 秒(进程内状态);
 * - 响应缓存 key = sha256(请求体),TTL 内相同请求直接回放(temperature>0 的业务方请自行关闭缓存语义);
 * - 计量逐请求落 gateway_usage,失败也落(供应商质量分析的数据面);计量异常绝不影响主流程;
 * - 限流为进程内滑动窗口(单实例形态);多实例部署应换 Redis 令牌桶,接口不变。
 */
@Slf4j
@Service
public class LlmGatewayImpl implements LlmGateway {

    private final BovinProperties props;
    private final ProviderClient providerClient;
    private final BaseMapper<GatewayUsage> usageMapper;

    /** 响应缓存:sha256(body) → 条目;惰性过期(读取时判 TTL),容量硬上限 */
    private final Map<String, CacheVal> cache = new ConcurrentHashMap<>();
    /** 熔断状态:provider → 打开截止时间戳(0=闭合) */
    private final Map<String, Long> breakerOpenUntil = new ConcurrentHashMap<>();
    /** 熔断计数:provider → 连续失败数 */
    private final Map<String, Integer> breakerFailures = new ConcurrentHashMap<>();
    /** 限流窗口:caller → 最近请求时间戳 */
    private final Map<String, Deque<Long>> rateWindows = new ConcurrentHashMap<>();

    private record CacheVal(String body, String provider, long bornAt) {
    }

    public LlmGatewayImpl(BovinProperties props, ProviderClient providerClient, BaseMapper<GatewayUsage> usageMapper) {
        this.props = props;
        this.providerClient = providerClient;
        this.usageMapper = usageMapper;
    }

    @Override
    public GatewayResult complete(GatewayRequest request) {
        BovinProperties.Gateway cfg = props.getGateway();
        String caller = request.caller() == null ? "anonymous" : request.caller();

        // ① 限流(配置了 api-keys 才启用鉴权+限流)
        String limited = checkRateLimit(cfg, caller);
        if (limited != null) {
            return new GatewayResult(429, "{\"error\":{\"message\":\"" + limited + "\"}}", null, false);
        }

        // ② 响应缓存
        String cacheKey = sha256(request.jsonBody());
        CacheVal hit = cache.get(cacheKey);
        long now = System.currentTimeMillis();
        if (hit != null && now - hit.bornAt() <= cfg.getCacheTtlSeconds() * 1000) {
            meter("CACHE", hit.provider, caller, null, null, 0, true, null);
            return new GatewayResult(200, hit.body(), hit.provider, true);
        }
        if (hit != null) cache.remove(cacheKey);

        // ③ 路由:enabled 且配置完整(name/baseUrl)的供应商按 priority 升序,熔断中的跳过
        List<BovinProperties.Gateway.Provider> candidates = cfg.getProviders().stream()
                .filter(p -> p.isEnabled() && p.getName() != null && !p.getName().isBlank()
                        && p.getBaseUrl() != null && !p.getBaseUrl().isBlank())
                .sorted(Comparator.comparingInt(BovinProperties.Gateway.Provider::getPriority))
                .toList();
        if (candidates.isEmpty()) {
            return new GatewayResult(503, "{\"error\":{\"message\":\"网关未配置可用供应商\"}}", null, false);
        }

        String lastError = null;
        for (BovinProperties.Gateway.Provider p : candidates) {
            if (breakerIsOpen(p.getName())) {
                log.warn("网关熔断中,跳过供应商: {}", p.getName());
                continue;
            }
            long t0 = System.currentTimeMillis();
            try {
                ForwardResult r = providerClient.forward(toView(p), request.jsonBody(), cfg.getTimeoutSeconds());
                breakerSuccess(p.getName());
                cache.put(cacheKey, new CacheVal(r.body(), p.getName(), System.currentTimeMillis()));
                meter(p.getName(), p.getModel(), caller, r.promptTokens(), r.completionTokens(),
                        (int) (System.currentTimeMillis() - t0), true, null);
                return new GatewayResult(200, r.body(), p.getName(), false);
            } catch (Exception e) {
                lastError = e.getMessage();
                breakerFailure(p.getName());
                meter(p.getName(), p.getModel(), caller, null, null,
                        (int) (System.currentTimeMillis() - t0), false, lastError);
                log.warn("网关供应商 {} 失败,failover: {}", p.getName(), lastError);
            }
        }
        return new GatewayResult(502, "{\"error\":{\"message\":\"全部供应商失败: "
                + (lastError == null ? "unknown" : lastError) + "\"}}", null, false);
    }

    /** 滑动窗口限流:返回 null=放行,否则为拒绝原因 */
    private String checkRateLimit(BovinProperties.Gateway cfg, String caller) {
        if (cfg.getApiKeys().isEmpty()) return null; // 演示形态:不鉴权不限流
        Integer rpm = cfg.getApiKeys().get(caller);
        if (rpm == null) return "无效的 API Key";
        Deque<Long> window = rateWindows.computeIfAbsent(caller, k -> new ArrayDeque<>());
        synchronized (window) {
            long now = System.currentTimeMillis();
            while (!window.isEmpty() && now - window.peekFirst() > 60_000) window.pollFirst();
            if (window.size() >= rpm) return "调用频率超限(" + rpm + " RPM)";
            window.addLast(now);
        }
        return null;
    }

    private boolean breakerIsOpen(String provider) {
        return breakerOpenUntil.getOrDefault(provider, 0L) > System.currentTimeMillis();
    }

    private void breakerSuccess(String provider) {
        breakerFailures.remove(provider);
        breakerOpenUntil.remove(provider);
    }

    private void breakerFailure(String provider) {
        int n = breakerFailures.merge(provider, 1, Integer::sum);
        if (n >= props.getGateway().getBreakerThreshold()) {
            long openMs = props.getGateway().getBreakerOpenSeconds() * 1000;
            breakerOpenUntil.put(provider, System.currentTimeMillis() + openMs);
            breakerFailures.remove(provider);
            log.warn("供应商 {} 连续失败 {} 次,熔断 {} 秒", provider, n, openMs / 1000);
        }
    }

    /** 计量落库:逐请求一行,失败也落;计量异常吞掉,不影响主流程 */
    private void meter(String provider, String model, String caller, Integer pt, Integer ct,
                       int costMs, boolean success, String error) {
        try {
            GatewayUsage u = new GatewayUsage();
            u.setTs(LocalDateTime.now());
            u.setProvider(provider);
            u.setModel(model);
            u.setCaller(caller);
            u.setPromptTokens(pt);
            u.setCompletionTokens(ct);
            u.setCostMs(costMs);
            u.setSuccess(success ? 1 : 0);
            if (error != null) u.setErrorMsg(error.length() > 500 ? error.substring(0, 500) : error);
            usageMapper.insert(u);
        } catch (Exception e) {
            log.warn("网关计量落库失败: {}", e.getMessage());
        }
    }

    private BovinProvider toView(BovinProperties.Gateway.Provider p) {
        return new BovinProvider(p.getName(), p.getBaseUrl(), p.getApiKey(), p.getModel());
    }

    static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
