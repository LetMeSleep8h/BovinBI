package com.eighthours.bovinbi.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** 执行请求:只凭两个 ID 定位解析结果,SQL 不经前端回传 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatExecuteReq {
    private Long queryId;
    private Integer parseId;
    private Long sessionId;
    /** 逐步确认模式(STEP)下必须为 true:表示用户已看过 SQL 并显式确认执行 */
    private Boolean approved;
}
