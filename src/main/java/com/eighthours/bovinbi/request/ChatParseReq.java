package com.eighthours.bovinbi.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 解析请求。datasetId 由调用方从 session 取出传入;queryId 首轮传 null,服务端生成 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatParseReq {
    private Long queryId;
    private Long sessionId;
    private Long datasetId;
    private String question;
    /** true 时只走规则引擎,不调 LLM */
    private Boolean disableLlm;
}