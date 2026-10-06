package com.eighthours.bovinbi.llm;

/**
 * 请求级模型选择(ThreadLocal):前端在引擎旁选模型(V4 Flash/V4 Pro/标准),
 * ChatService 进入时写入、finally 清理;LangChain4jClient / Agent 循环 /
 * 两段式教学版读取当前请求应使用的模型,空则回落 bovin.llm.model 配置默认。
 * 异步链路(ask/stream)由 ChatController 显式传递进子线程,与 UserContext 同法。
 */
public final class ModelContext {

    private static final ThreadLocal<String> MODEL = new ThreadLocal<>();

    private ModelContext() {
    }

    public static void set(String model) {
        if (model != null && !model.isBlank()) {
            MODEL.set(model.trim());
        }
    }

    /** 当前请求的模型;未设置返回 null(调用方回落默认) */
    public static String get() {
        return MODEL.get();
    }

    public static String getOrDefault(String defaultModel) {
        String m = MODEL.get();
        return (m == null || m.isBlank()) ? defaultModel : m;
    }

    public static void clear() {
        MODEL.remove();
    }
}
