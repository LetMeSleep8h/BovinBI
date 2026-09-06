package com.eighthours.bovinbi.service;

/** SQL 生成结果:sql 为 null 表示"无法理解该问题"(优雅降级) */
public record SqlResult(String sql, String explanation, String engine) {
}
