package com.eighthours.bovinbi.llm;

import com.fasterxml.jackson.databind.JsonNode;

/** LLM 客户端抽象(便于扩展多供应商):chat 为对话补全,extractJson 从回复中提取 JSON */
public interface LlmClient {

    /**
     * @return 模型原始文本回复(期望为 JSON)
     */
    String chat(String systemPrompt, String userPrompt);

    /**
     * 从模型回复中提取 JSON(容忍 markdown 代码块包裹)
     */
    JsonNode extractJson(String raw);
}
