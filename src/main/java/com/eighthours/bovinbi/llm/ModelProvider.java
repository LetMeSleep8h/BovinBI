package com.eighthours.bovinbi.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模型提供者(对齐 SuperSonic 的 dev.langchain4j.provider.ModelProvider):
 * 静态注册表 + 工厂路由,新增供应商只需实现 ModelFactory 并注册,调用方零改动。
 */
public class ModelProvider {

    private static final Map<String, ModelFactory> FACTORIES = new ConcurrentHashMap<>();

    public static void add(String provider, ModelFactory factory) {
        FACTORIES.put(provider.toUpperCase(), factory);
    }

    public static ChatLanguageModel getChatModel(ChatModelConfig modelConfig) {
        String provider = modelConfig.getProvider() == null ? ChatModelConfig.PROVIDER_OPEN_AI
                : modelConfig.getProvider().toUpperCase();
        ModelFactory factory = FACTORIES.get(provider);
        if (factory == null) {
            throw new IllegalArgumentException("未注册的模型 provider: " + provider
                    + ",已注册: " + FACTORIES.keySet());
        }
        return factory.createChatModel(modelConfig);
    }
}
