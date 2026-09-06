package com.eighthours.bovinbi.llm;

import lombok.Builder;
import lombok.Data;

/**
 * 聊天模型配置(对齐 SuperSonic 的 ChatModelConfig):描述一次模型接入的全部参数,
 * 由 ModelProvider 按 provider 路由到具体 ModelFactory 构建模型实例。
 */
@Data
@Builder
public class ChatModelConfig {

    public static final String PROVIDER_OPEN_AI = "OPEN_AI";

    /** 供应商标识:OPEN_AI(OpenAI 兼容协议,DeepSeek/通义千问/GLM/OpenAI 通用) */
    private String provider;
    private String baseUrl;
    private String apiKey;
    private String modelName;
    private Double temperature;
    /** 超时(秒) */
    private Integer timeOut;
    private Integer maxRetries;
    private Boolean logRequests;
    private Boolean logResponses;
}
