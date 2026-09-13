package com.eighthours.bovinbi.agent;

/**
 * ThreadLocal 方式向 @Tool 方法传递请求级运行上下文。
 * 面试要点:LangChain4j 0.36 的工具方法没有请求级上下文参数(1.x 才引入 ToolContext),
 * 这里用 ThreadLocal 在 orchestrator 里 set / try-finally clear,保证同一次问答的
 * 工具调用共享预算与结果登记,且调用结束不泄漏。
 */
public final class AgentContextHolder {

    private static final ThreadLocal<AgentRunContext> HOLDER = new ThreadLocal<>();

    private AgentContextHolder() {
    }

    public static void set(AgentRunContext ctx) {
        HOLDER.set(ctx);
    }

    public static AgentRunContext get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
