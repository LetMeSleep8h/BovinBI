package com.eighthours.bovinbi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** LLM 网关调用量化记录:每请求一行,供成本核算与供应商质量分析 */
@Data
@TableName("gateway_usage")
public class GatewayUsage {
    @TableId(type = IdType.AUTO)
    private Long id;
    private LocalDateTime ts;
    /** 供应商标识(gateway.providers[].name);cache 命中时为 CACHE */
    private String provider;
    private String model;
    /** 调用方 API key(脱敏后存储由调用方保证) */
    private String caller;
    private Integer promptTokens;
    private Integer completionTokens;
    private Integer costMs;
    /** 1=成功 0=失败 */
    private Integer success;
    private String errorMsg;
    private LocalDateTime createdAt;
}
