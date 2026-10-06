package com.eighthours.bovinbi.llm;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.security.UserContext;
import com.eighthours.bovinbi.service.TokenUsageService;
import com.eighthours.bovinbi.config.BovinProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LangChain4j 客户端(项目唯一的 LLM 出口):
 * 按 OpenAI 兼容协议懒构建模型实例(DeepSeek / 通义千问 / GLM / OpenAI 通用,换供应商只改配置)。
 * provider=mock 时不会走到这里,保证零外部依赖启动;调用层面向 LlmClient 抽象,便于测试替身。
 */
@Slf4j
@Component
public class LangChain4jClient implements LlmClient {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final BovinProperties props;
    private final ObjectMapper objectMapper;
    /** 按模型名缓存的实例(V4 Flash/V4 Pro/标准…并发安全);DNS 友好重试在外层 */
    private final java.util.concurrent.ConcurrentHashMap<String, ChatLanguageModel> models =
            new java.util.concurrent.ConcurrentHashMap<>();

    private final TokenUsageService tokenUsageService;

    public LangChain4jClient(BovinProperties props, ObjectMapper objectMapper,
                             TokenUsageService tokenUsageService) {
        this.props = props;
        this.objectMapper = objectMapper;
        this.tokenUsageService = tokenUsageService;
    }

    /**
     * 当前请求应使用的模型实例:ModelContext(前端按请求选择)优先,
     * 回落 bovin.llm.model 默认;实例按模型名缓存,创建一次复用。
     */
    private ChatLanguageModel model() {
        BovinProperties.Llm c = props.getLlm();
        String name = ModelContext.getOrDefault(c.getModel());
        return models.computeIfAbsent(name, n -> OpenAiChatModel.builder()
                .baseUrl(c.getBaseUrl())
                .apiKey(c.getApiKey())
                .modelName(n)
                .temperature(c.getTemperature())
                .maxRetries(c.getMaxRetries())
                .timeout(Duration.ofSeconds(c.getTimeoutSeconds()))
                .logRequests(c.isLogRequests())
                .logResponses(c.isLogResponses())
                .build());
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        try {
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt));
            Response<AiMessage> resp = generateWithDnsRetry(messages);
            recordUsage(resp);
            String content = resp.content().text();
            if (content == null || content.isBlank()) {
                throw new BizException(502, "LLM 返回内容为空");
            }
            return content;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("LLM 调用失败: {}", e.getMessage());
            throw new BizException(502, "LLM 调用失败: " + e.getMessage());
        }
    }

    /**
     * DNS 友好重试:LLM 端点的多 A 记录里可能混有不可达 IP(如 DeepSeek 的
     * 103.220.64.100),OkHttp 无 Happy Eyeballs、连上坏 IP 只能等 callTimeout。
     * JVM DNS 缓存已调短(SecurityProperty ttl=5s),外层最多 2 次重试,
     * 重新解析后大概率落到可达 IP —— 实测第 2 次即成功(925ms)。
     */
    private Response<AiMessage> generateWithDnsRetry(List<ChatMessage> messages) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                return model().generate(messages);
            } catch (RuntimeException e) {
                String msg = String.valueOf(e.getCause() != null ? e.getCause() : e);
                if (!msg.contains("timeout") && !msg.contains("InterruptedIOException")) {
                    throw e; // 非 DNS/网络超时类失败不重试(如 401/429 由调用方处理)
                }
                last = e;
                log.warn("LLM 调用疑似撞不可达 IP(第 {} 次),重试换解析: {}", attempt, msg.substring(0, Math.min(80, msg.length())));
            }
        }
        throw last;
    }

    /** token 用量计量:每用户每日独立累计(uid 取请求线程,无用户态则跳过) */
    private void recordUsage(Response<AiMessage> resp) {
        try {
            var usage = resp.tokenUsage();
            tokenUsageService.record(UserContext.uid(),
                    usage == null ? null : usage.inputTokenCount(),
                    usage == null ? null : usage.outputTokenCount());
        } catch (Exception ignore) {
            // 计量失败绝不影响主链路
        }
    }

    /** 从模型回复中提取 JSON(容忍 markdown 代码块包裹) */
    @Override
    public JsonNode extractJson(String raw) {
        try {
            return objectMapper.readTree(raw);
        } catch (Exception ignore) {
            // try to locate {...}
        }
        Matcher m = JSON_BLOCK.matcher(raw);
        if (m.find()) {
            try {
                return objectMapper.readTree(m.group());
            } catch (Exception ignore) {
            }
        }
        throw new BizException(502, "无法从模型回复中解析出 JSON");
    }
}
