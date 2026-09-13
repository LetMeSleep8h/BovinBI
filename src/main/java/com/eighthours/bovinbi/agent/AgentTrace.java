package com.eighthours.bovinbi.agent;

/**
 * Agent 运行轨迹(可观测性):记录一次问答中每次工具调用的
 * 序号/工具名/入参摘要/是否成功/耗时/结果摘要。
 * 面试要点:Agent 的行为不再是黑盒 —— 轨迹随 AnswerPayload 落库,
 * 既支撑前端"思考过程"展示,也是成本分析的第一手数据
 * (平均工具调用数、SQL 重试率、预算触顶率都从这里统计)。
 */
public final class AgentTrace {

    private AgentTrace() {
    }

    /**
     * 单次工具调用记录。
     * 条数被运行预算硬性约束(不超过 maxToolCalls),天然有界,不会无界增长。
     */
    public record ToolCall(int seq, String tool, String args, boolean ok, int tookMs, String summary) {
    }
}
