package com.eighthours.bovinbi.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;

/**
 * 模型工厂(对齐 SuperSonic 的 dev.langchain4j.provider.ModelFactory):
 * 每种供应商一个实现,注册到 ModelProvider 静态注册表中,按 provider 名称路由。
 */
public interface ModelFactory {

    ChatLanguageModel createChatModel(ChatModelConfig modelConfig);
}
