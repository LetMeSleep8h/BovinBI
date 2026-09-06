package com.eighthours.bovinbi.service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * 相对时间解析:把"上个月 / 近30天 / 今年 / 2026年3月"等自然语言时间解析为标准区间 [start, end)。
 * 面试要点:NL2SQL 失败案例中约 1/3 源于时间理解,把时间解析从 LLM 挪到确定性代码可显著降低出错率。
 */
public record TimeRange(LocalDate start, LocalDate endExclusive, String label) {

    public static final DateTimeFormatter F = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** 生成 SQL 日期过滤条件(半开区间,利于索引) */
    public String toSqlCondition(String dateColumn) {
        return dateColumn + " >= '" + start.format(F) + "' AND " + dateColumn + " < '" + endExclusive.format(F) + "'";
    }

    /** 缓存 key 用的归一化表示 */
    public String cacheKey() {
        return start.format(F) + "~" + endExclusive.format(F);
    }
}
