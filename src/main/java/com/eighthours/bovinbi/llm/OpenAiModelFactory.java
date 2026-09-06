package com.eighthours.bovinbi.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * OpenAI 兼容模型工厂:
 * DeepSeek / 通义千问(DashScope compatible-mode) / GLM / OpenAI 均为 OpenAI 兼容协议,
 * 仅需替换 baseUrl 与 modelName 即可切换。
 */
@Component
public class OpenAiModelFactory implements ModelFactory, InitializingBean {

    @Override
    public ChatLanguageModel createChatModel(ChatModelConfig modelConfig) {
        return OpenAiChatModel.builder()
                .baseUrl(modelConfig.getBaseUrl())
                .modelName(modelConfig.getModelName())
                .apiKey(modelConfig.getApiKey())
                .temperature(modelConfig.getTemperature())
                .maxRetries(modelConfig.getMaxRetries())
                .timeout(Duration.ofSeconds(modelConfig.getTimeOut()))
                .logRequests(modelConfig.getLogRequests())
                .logResponses(modelConfig.getLogResponses())
                .build();
    }

    @Override
    public void afterPropertiesSet() {
        ModelProvider.add(ChatModelConfig.PROVIDER_OPEN_AI, this);
    }
}
