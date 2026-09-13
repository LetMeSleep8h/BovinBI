package com.eighthours.bovinbi.dto;

import com.eighthours.bovinbi.agent.AgentTrace;
import lombok.Data;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/** 助手回答的结构化载荷(持久化到 chat_message.payload,前端据此渲染) */
@Data
public class AnswerPayload {
    private String sql;
    private String explanation;
    private List<ColInfo> columns = new ArrayList<>();
    private List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
    private int rowCount;
    private ChartSpec chart;
    private long tookMs;
    private boolean cacheHit;
    /** RULE / LLM / LLM(修复) / AGENT / AGENT(降级RULE) / CACHE */
    private String engine;
    /** Agent 模式:工具调用轨迹(思考过程可观测,条数受预算约束天然有界) */
    private List<AgentTrace.ToolCall> trace;
    /** 无法回答时的兜底提示 */
    private boolean fallback;
    private String fallbackHint;
}
