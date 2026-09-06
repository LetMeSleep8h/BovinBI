package com.eighthours.bovinbi.llm;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.output.Response;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * LangChain4j 客户端(替换原手写 WebClient 版,对齐 SuperSonic 的模型接入方式):
 * ChatLanguageModel 由 ModelProvider 按配置路由构建,业务代码只面向 LlmClient 抽象。
 * provider=mock 时不构建模型实例,调用即抛错,由管线降级到规则引擎。
 */
@Slf4j
@Component
public class LangChain4jClient implements LlmClient {

    private static final Pattern JSON_BLOCK = Pattern.compile("\\{.*}", Pattern.DOTALL);

    private final BovinProperties props;
    private final ObjectMapper objectMapper;
    private volatile ChatLanguageModel chatModel;

    public LangChain4jClient(BovinProperties props, ObjectMapper objectMapper) {
        this.props = props;
        this.objectMapper = objectMapper;
    }

    /** 懒构建:首次调用才创建模型实例,保证 provider=mock 时零外部依赖启动 */
    private ChatLanguageModel model() {
        if (chatModel == null) {
            synchronized (this) {
                if (chatModel == null) {
                    BovinProperties.Llm cfg = props.getLlm();
                    chatModel = ModelProvider.getChatModel(ChatModelConfig.builder()
                            .provider(ChatModelConfig.PROVIDER_OPEN_AI)
                            .baseUrl(cfg.getBaseUrl())
                            .apiKey(cfg.getApiKey())
                            .modelName(cfg.getModel())
                            .temperature(cfg.getTemperature())
                            .timeOut(cfg.getTimeoutSeconds())
                            .maxRetries(cfg.getMaxRetries())
                            .logRequests(cfg.isLogRequests())
                            .logResponses(cfg.isLogResponses())
                            .build());
                }
            }
        }
        return chatModel;
    }

    @Override
    public String chat(String systemPrompt, String userPrompt) {
        try {
            List<ChatMessage> messages = List.of(
                    SystemMessage.from(systemPrompt),
                    UserMessage.from(userPrompt));
            Response<AiMessage> resp = model().generate(messages);
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

    /** 从模型回复中提取 JSON(容忍 markdown 代码块包裹) */
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
