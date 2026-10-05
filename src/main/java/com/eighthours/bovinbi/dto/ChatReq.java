package com.eighthours.bovinbi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatReq(@NotNull Long sessionId, @NotBlank String question,
                      /** 提问引擎:java(默认,底座自带链路) / python(Python Agent,经 MCP 回环执行) */
                      String engine,
                      /** 多步计划(/plan)用:规划与执行的目标数据集 */
                      Long datasetId) {
}
