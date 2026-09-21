package com.eighthours.bovinbi.dto;

import com.eighthours.bovinbi.service.TimeRange;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 一条候选解析:parse 阶段的产物,execute 阶段的输入 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SemanticParseInfo {
    private Integer parseId;       // 候选序号,当前固定 1
    /** 与 AnswerPayload.engine / query_log.engine 同口径:RULE / LLM / LLM(修复) / AGENT / CACHE / CHIT_CHAT */
    private String engine;
    private String question;
    private String sql;            // 守护后的最终 SQL,parse 阶段不执行;闲聊时为 null
    private String explanation;
    private TimeRange timeRange;
    private Long datasetId;
}
