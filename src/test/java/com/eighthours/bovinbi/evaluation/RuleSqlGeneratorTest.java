package com.eighthours.bovinbi.evaluation;

import com.eighthours.bovinbi.service.RuleSqlGenerator;
import com.eighthours.bovinbi.service.SqlGenContext;
import com.eighthours.bovinbi.service.SqlResult;
import com.eighthours.bovinbi.service.TimeRange;
import com.eighthours.bovinbi.service.TimeRangeParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.*;

/** 规则引擎模板单测(牧场养殖口径):固定今天=2026-09-05,断言生成的 SQL 关键结构 */
class RuleSqlGeneratorTest {

    private final RuleSqlGenerator generator = new RuleSqlGenerator();
    private final TimeRangeParser parser = new TimeRangeParser(LocalDate.of(2026, 9, 5));

    private SqlResult gen(String question) {
        TimeRange tr = parser.parse(question);
        // 规则引擎按领域口径硬编码,不消费 Schema 文本与白名单,传 null
        return generator.generate(new SqlGenContext(question, tr, null, null));
    }

    private String norm(String sql) {
        return sql == null ? "" : sql.toLowerCase().replaceAll("\\s+", "");
    }

    @Test
    void totalMetricWithTimeFilter() {
        SqlResult r = gen("今年总产奶量");
        String n = norm(r.sql());
        assertTrue(n.contains("sum(m.milk_yield)"));
        assertTrue(n.contains("m.record_date>='2026-01-01'"));
        assertTrue(n.contains("<'2027-01-01'"), "半开区间:结束条件应为 < 下一年");
        assertTrue(n.endsWith("limit1000") || n.contains("limit1000"));
    }

    @Test
    void monthlyTrend() {
        String n = norm(gen("近12个月每月产奶量趋势").sql());
        assertTrue(n.contains("date_format(m.record_date,'%y-%m')"));
        assertTrue(n.contains("groupby1"));
        assertTrue(n.contains("orderby1"));
    }

    @Test
    void trendWithDimension() {
        String n = norm(gen("近3个月每月各品种产奶量").sql());
        assertTrue(n.contains("c.breed"));
        assertTrue(n.contains("groupby1,2"), "时间×维度应按两列分组");
    }

    @Test
    void topNAscendingForWorst() {
        String n = norm(gen("产奶量最差的5个牧场").sql());
        assertTrue(n.contains("f.farm_name"));
        assertTrue(n.contains("orderby2asc"));
        assertTrue(n.contains("limit5"));
    }

    @Test
    void ratioQuery() {
        String n = norm(gen("上个月各品种产奶量占比").sql());
        assertTrue(n.contains("c.breed"));
        assertTrue(n.contains("orderby2desc"));
        assertTrue(n.contains("m.record_date>='2026-08-01'"));
    }

    @Test
    void momComparison() {
        SqlResult r = gen("上个月产奶量环比");
        String n = norm(r.sql());
        assertTrue(n.contains("unionall"));
        assertTrue(n.contains("'2026年8月'"));
        assertTrue(n.contains("'2026年7月'"));
    }

    @Test
    void regionValueFilter() {
        String n = norm(gen("西北地区牧场的产奶量").sql());
        assertTrue(n.contains("f.region='西北'"), "维度值过滤应生成 WHERE 条件");
    }

    @Test
    void scaleValueFilter() {
        String n = norm(gen("大型牧场的平均单产").sql());
        assertTrue(n.contains("f.scale='大型'"));
        assertTrue(n.contains("avg(m.milk_yield)"));
    }

    @Test
    void weekendFilter() {
        String n = norm(gen("周末的产奶量").sql());
        assertTrue(n.contains("dayofweek(m.record_date)in(1,7)"));
        assertTrue(n.contains("sum(m.milk_yield)"));
    }

    @Test
    void unknownQuestionReturnsNull() {
        assertNull(gen("你好呀"), "无法识别的问题应返回 null 走兜底");
        assertNull(gen("jsfuwq8923"));
    }

    @Test
    void metricDisambiguation() {
        String n = norm(gen("今年平均单产").sql());
        assertTrue(n.contains("avg(m.milk_yield)"), "平均单产应为 AVG 而非 SUM");
        assertFalse(n.contains("sum(m.milk_yield)"));
    }

    @Test
    void lactationStageFilter() {
        String n = norm(gen("泌乳初期的乳脂率").sql());
        assertTrue(n.contains("m.lactation_stage='泌乳初期'"));
        assertTrue(n.contains("avg(m.fat_rate)"));
    }
}
