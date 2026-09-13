package com.eighthours.bovinbi.agent;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;

/**
 * BovinBI 数据分析 Agent(AiServices 声明式接口):
 * - chatModel + 工具集(BovinTools) + 会话记忆(memoryId=会话 id)由 AgentConfig 组装;
 * - 框架内部维护 tool-calling 循环:模型决定调用哪个工具 → 执行 → 结果回喂 → 直至给出最终回答;
 * - 与旧管线的本质区别:第 3~5 步(取 Schema/生成/执行/修复)的编排权
 *   从 Nl2SqlService 的固定代码转移到模型,自主性由工具层预算约束。
 */
public interface BovinAgent {

    /**
     * 单轮问答入口。
     *
     * @param memoryId 会话 id,同会话共享记忆窗口(多轮追问);无会话场景(评测)由 orchestrator 生成一次性 id
     * @param message  已注入 SideInfo(时间区间/今天日期)的用户消息
     * @return 模型最终回答(prompt 约定为 JSON:{"final_sql": "...", "explanation": "..."})
     */
    String chat(@MemoryId long memoryId, @UserMessage String message);
}
