package com.eighthours.bovinbi.evaluation;

import com.eighthours.bovinbi.service.TimeRange;
import com.eighthours.bovinbi.service.TimeRangeParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 时间解析单测:固定"今天"=2026-09-05,可复现 */
class TimeRangeParserTest {

    private final TimeRangeParser parser = new TimeRangeParser(LocalDate.of(2026, 9, 5));

    @Test
    void lastMonth() {
        TimeRange tr = parser.parse("上个月销售额");
        assertEquals(LocalDate.of(2026, 8, 1), tr.start());
        assertEquals(LocalDate.of(2026, 9, 1), tr.endExclusive());
        assertEquals("2026年8月", tr.label());
    }

    @Test
    void thisYear() {
        TimeRange tr = parser.parse("今年总销售额");
        assertEquals(LocalDate.of(2026, 1, 1), tr.start());
        assertEquals(LocalDate.of(2027, 1, 1), tr.endExclusive());
    }

    @Test
    void last30Days() {
        TimeRange tr = parser.parse("近30天销量趋势");
        assertEquals(LocalDate.of(2026, 8, 7), tr.start());
        assertEquals(LocalDate.of(2026, 9, 6), tr.endExclusive());
    }

    @Test
    void recent3Months() {
        TimeRange tr = parser.parse("近3个月每月利润");
        assertEquals(LocalDate.of(2026, 7, 1), tr.start());
    }

    @Test
    void explicitYearMonth() {
        TimeRange tr = parser.parse("2026年3月订单数");
        assertEquals(LocalDate.of(2026, 3, 1), tr.start());
        assertEquals(LocalDate.of(2026, 4, 1), tr.endExclusive());
    }

    @Test
    void explicitYear() {
        TimeRange tr = parser.parse("2025年销售额");
        assertEquals(LocalDate.of(2025, 1, 1), tr.start());
        assertEquals(LocalDate.of(2026, 1, 1), tr.endExclusive());
    }

    @Test
    void quarter() {
        TimeRange tr = parser.parse("今年Q2销售额");
        assertEquals(LocalDate.of(2026, 4, 1), tr.start());
        assertEquals(LocalDate.of(2026, 7, 1), tr.endExclusive());
    }

    @Test
    void monthOnlyPast() {
        TimeRange tr = parser.parse("3月的销售额");
        assertEquals(LocalDate.of(2026, 3, 1), tr.start());
    }

    @Test
    void monthOnlyFutureIsLastYear() {
        TimeRange tr = parser.parse("12月的销售额");
        assertEquals(LocalDate.of(2025, 12, 1), tr.start());
    }

    @Test
    void noTimeReturnsNull() {
        assertNull(parser.parse("销售额Top10商品"));
    }

    @Test
    void sqlCondition() {
        TimeRange tr = parser.parse("上个月销售额");
        assertEquals("d.order_date >= '2026-08-01' AND d.order_date < '2026-09-01'", tr.toSqlCondition("d.order_date"));
    }
}
