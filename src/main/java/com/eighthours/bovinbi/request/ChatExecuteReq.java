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
}
