package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.dto.AnswerPayload;

/**
 * 多 Agent 流水线接口("一个 Agent 只干一件事"):
 * SQL Agent(写 SQL) → Reviewer(确定性守护/时间对账 + 可选 LLM 语义审查) → Repair Agent(按拒绝原因修复),
 * 审查通过后执行并组装载荷。bovin.chat.engine=multi-agent 时由 Nl2SqlService 分发进来,
 * 失败时抛出异常,由外壳降级规则引擎(与单 Agent 的降级契约一致)。
 */
public interface MultiAgentService {

    /**
     * @param sessionId 预留(当前流水线为单轮无记忆形态,与评测一次性 memoryId 语义对齐)
     */
    AnswerPayload answer(Long datasetId, String question, Long sessionId);
}
