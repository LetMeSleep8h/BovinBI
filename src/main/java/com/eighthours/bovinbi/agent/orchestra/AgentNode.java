package com.eighthours.bovinbi.agent.orchestra;

/**
 * 编排节点(多 Agent 流水线中的"一个 Agent"):一个节点只做一件事,通过共享黑板
 * {@link OrchestraContext} 读上游产物、写自己的产物。
 * 契约:
 * - 审查类节点不通过时把原因写入 ctx.rejection 并正常返回(错误即数据,驱动修复环);
 * - 基础设施故障(LLM 不可达等)直接抛异常,由外壳(Nl2SqlService)降级规则引擎;
 * - 节点自行记录轨迹(ctx.record),框架只负责"何时跑谁"。
 */
public interface AgentNode {

    /** 节点名,用于轨迹与日志(如 schema / sql_writer / reviewer) */
    String name();

    /** 执行一个步骤;产出全部写入 ctx */
    void run(OrchestraContext ctx) throws Exception;
}
