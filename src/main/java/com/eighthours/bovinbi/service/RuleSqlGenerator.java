package com.eighthours.bovinbi.service;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则式 SQL 生成器(离线兜底引擎,口径:智慧牧场·奶牛养殖):
 * - provider=mock 时的主引擎,保证无 API Key 也能完整演示;
 * - provider=openai 时作为 LLM 链路(生成/守护/执行/自修复)全部失败后的最终兜底。
 * 覆盖的问题形态:总体指标、时间趋势(含时间×维度)、TopN、占比、环比/同比、
 * 维度汇总、维度值过滤(地区/牧场规模/品种/泌乳阶段/周末等)。
 * 注意:规则引擎按本数据集口径硬编码,不依赖 Schema 召回结果(那是 LLM 引擎的输入)。
 */
@Component
public class RuleSqlGenerator {

    private static final String FROM = """
            FROM dwh_fact_milk m
            LEFT JOIN dwh_dim_cattle c ON m.cattle_id = c.id
            LEFT JOIN dwh_dim_farm f ON m.farm_id = f.id""";

    private record Metric(String alias, String expr, String desc) {
    }
    private record Dim(String alias, String expr) {
    }

    public SqlResult generate(SqlGenContext ctx) {
        String q = ctx.question();
        TimeRange tr = ctx.timeRange();
        String filter = valueFilter(q);

        // ---------- 环比 ----------
        if (q.contains("环比")) {
            Metric m = metricOrDefault(q);
            TimeRange cur = tr != null ? tr : monthOffset(1);
            TimeRange prev = monthRange(cur.start().minusMonths(1));
            String sql = """
                    SELECT '%s' AS 期间, %s AS %s
                    %s
                    %s
                    UNION ALL
                    SELECT '%s' AS 期间, %s AS %s
                    %s
                    %s
                    LIMIT 1000""".formatted(
                    cur.label(), m.expr(), m.alias(), FROM, whereOf(cur, filter),
                    prev.label(), m.expr(), m.alias(), FROM, whereOf(prev, filter));
            return new SqlResult(sql, "对比 %s 与 %s 的%s".formatted(cur.label(), prev.label(), m.alias()));
        }

        // ---------- 同比 ----------
        if (q.contains("同比")) {
            Metric m = metricOrDefault(q);
            TimeRange base = tr != null ? tr : monthOffset(1);
            TimeRange lastYear = new TimeRange(base.start().minusYears(1), base.endExclusive().minusYears(1),
                    base.label() + "(去年同期)");
            String sql = """
                    SELECT '%s' AS 期间, %s AS %s
                    %s
                    %s
                    UNION ALL
                    SELECT '%s' AS 期间, %s AS %s
                    %s
                    %s
                    LIMIT 1000""".formatted(
                    base.label(), m.expr(), m.alias(), FROM, whereOf(base, filter),
                    lastYear.label(), m.expr(), m.alias(), FROM, whereOf(lastYear, filter));
            return new SqlResult(sql, "同比:对比 %s 与去年同期%s".formatted(base.label(), m.alias()));
        }

        // ---------- TopN ----------
        if (containsAny(q, "top", "Top", "TOP", "排行", "前10", "前5", "前3", "最高", "最好", "最差", "最少",
                "最低", "最不畅销")) {
            Metric m = metricOrDefault(q);
            Dim d = dimOrDefault(q);
            int n = topN(q);
            boolean asc = q.contains("最低") || q.contains("最差") || q.contains("最少");
            String sql = """
                    SELECT %s AS %s, %s AS %s
                    %s%s
                    GROUP BY 1
                    ORDER BY 2 %s
                    LIMIT %d""".formatted(d.expr(), d.alias(), m.expr(), m.alias(),
                    FROM, whereOf(tr, filter), asc ? "ASC" : "DESC", n);
            return new SqlResult(sql, "%s%sTop%d(%s)%s".formatted(
                    tr == null ? "" : tr.label() + " ", d.alias(), n, m.alias(), asc ? ",升序" : ""));
        }

        // ---------- 占比/分布 ----------
        if (containsAny(q, "占比", "份额", "构成", "分布", "结构")) {
            Metric m = metricOrDefault(q);
            Dim d = dimOrDefault(q);
            String sql = """
                    SELECT %s AS %s, %s AS %s
                    %s%s
                    GROUP BY 1
                    ORDER BY 2 DESC
                    LIMIT 1000""".formatted(d.expr(), d.alias(), m.expr(), m.alias(), FROM, whereOf(tr, filter));
            return new SqlResult(sql, "按%s统计%s%s".formatted(d.alias(), tr == null ? "" : tr.label(), m.alias()));
        }

        // ---------- 时间趋势(支持 时间×维度 组合,如"每月各牧场产奶量") ----------
        if (containsAny(q, "趋势", "按月", "每月", "每个月", "月度", "走势", "变化", "按天", "每天", "每日", "按周", "每周")) {
            Metric m = metricOrDefault(q);
            Dim d = dim(q);
            boolean byDay = containsAny(q, "按天", "每天", "每日");
            boolean byWeek = containsAny(q, "按周", "每周");
            String dateExpr = byDay ? "DATE_FORMAT(m.record_date, '%Y-%m-%d')"
                    : byWeek ? "DATE_FORMAT(m.record_date, '%x-W%v')"
                    : "DATE_FORMAT(m.record_date, '%Y-%m')";
            String label = byDay ? "日期" : byWeek ? "周" : "月份";
            String where = whereOf(tr, filter);
            if (d != null) {
                String sql = """
                        SELECT %s AS %s, %s AS %s, %s AS %s
                        %s%s
                        GROUP BY 1, 2
                        ORDER BY 1
                        LIMIT 1000""".formatted(dateExpr, label, d.expr(), d.alias(), m.expr(), m.alias(), FROM, where);
                return new SqlResult(sql, "按%s×%s统计%s%s".formatted(label, d.alias(),
                        tr == null ? "" : tr.label(), m.alias()));
            }
            String sql = """
                    SELECT %s AS %s, %s AS %s
                    %s%s
                    GROUP BY 1
                    ORDER BY 1
                    LIMIT 1000""".formatted(dateExpr, label, m.expr(), m.alias(), FROM, where);
            return new SqlResult(sql, "按%s统计%s%s趋势".formatted(label, tr == null ? "" : tr.label(), m.alias()));
        }

        // ---------- 按维度汇总 ----------
        if (dim(q) != null && containsAny(q, "各", "每个", "按", "分别", "对比")) {
            Metric m = metricOrDefault(q);
            Dim d = dim(q);
            String sql = """
                    SELECT %s AS %s, %s AS %s
                    %s%s
                    GROUP BY 1
                    ORDER BY 2 DESC
                    LIMIT 1000""".formatted(d.expr(), d.alias(), m.expr(), m.alias(), FROM, whereOf(tr, filter));
            return new SqlResult(sql, "按%s统计%s%s".formatted(d.alias(), tr == null ? "" : tr.label(), m.alias()));
        }

        // ---------- 带维度值过滤的总指标(如"西北地区牧场的产奶量"/"泌乳初期的乳脂率") ----------
        if (filter != null) {
            Metric m = metricOrDefault(q);
            String sql = "SELECT " + m.expr() + " AS " + m.alias() + "\n" + FROM
                    + whereOf(tr, filter) + "\nLIMIT 1000";
            return new SqlResult(sql, (tr == null ? "" : tr.label()) + "满足条件的" + m.alias());
        }

        // ---------- 总体指标(必须显式提到指标,否则视为无法理解 → 走兜底) ----------
        Metric m = metric(q);
        if (m == null) {
            return null;
        }
        String sql = "SELECT " + m.expr() + " AS " + m.alias() + "\n" + FROM
                + whereOf(tr, null) + "\nLIMIT 1000";
        return new SqlResult(sql, (tr == null ? "全部数据" : tr.label()) + "的" + m.alias() + "(" + m.desc() + ")");
    }

    // ---------------- 识别逻辑 ----------------

    /** 维度值过滤:地区、牧场规模、品种、泌乳阶段、周末/工作日 → WHERE 条件 */
    private String valueFilter(String q) {
        if (q.contains("西北")) return "f.region = '西北'";
        if (q.contains("华北")) return "f.region = '华北'";
        if (q.contains("华东")) return "f.region = '华东'";
        if (q.contains("东北")) return "f.region = '东北'";
        if (q.contains("华中")) return "f.region = '华中'";
        if (q.contains("西南")) return "f.region = '西南'";
        if (q.contains("大型")) return "f.scale = '大型'";
        if (q.contains("中型")) return "f.scale = '中型'";
        if (q.contains("小型")) return "f.scale = '小型'";
        if (q.contains("荷斯坦")) return "c.breed = '荷斯坦'";
        if (q.contains("西门塔尔")) return "c.breed = '西门塔尔'";
        if (q.contains("娟姗")) return "c.breed = '娟姗'";
        if (q.contains("泌乳初期") || q.contains("产犊初期")) return "m.lactation_stage = '泌乳初期'";
        if (q.contains("泌乳中期")) return "m.lactation_stage = '泌乳中期'";
        if (q.contains("泌乳后期")) return "m.lactation_stage = '泌乳后期'";
        if (q.contains("周末")) return "DAYOFWEEK(m.record_date) IN (1, 7)";
        if (q.contains("工作日")) return "DAYOFWEEK(m.record_date) NOT IN (1, 7)";
        return null;
    }

    /** 无任何指标关键词时返回 null(调用方据此走兜底或默认产奶量) */
    private Metric metric(String q) {
        // 顺序敏感:长词优先,避免"乳蛋白率"被"蛋白率"抢先、"平均单产"被"产奶量"抢先
        if (q.contains("乳蛋白率") || q.contains("蛋白率")) {
            return new Metric("乳蛋白率", "ROUND(AVG(m.protein_rate), 2)", "AVG(m.protein_rate)");
        }
        if (q.contains("乳脂率") || q.contains("脂肪率")) {
            return new Metric("乳脂率", "ROUND(AVG(m.fat_rate), 2)", "AVG(m.fat_rate)");
        }
        if (q.contains("单产")) {
            return new Metric("平均单产", "ROUND(AVG(m.milk_yield), 2)", "AVG(m.milk_yield),单次挤奶均值");
        }
        if (containsAny(q, "泌乳牛数", "牛只数", "牛的数量", "头数", "牛数", "泌乳牛", "在泌牛")) {
            return new Metric("泌乳牛数", "COUNT(DISTINCT m.cattle_id)", "COUNT(DISTINCT cattle_id)");
        }
        if (containsAny(q, "产奶量", "奶量", "产奶", "挤奶量", "产量")) {
            return new Metric("产奶量", "ROUND(SUM(m.milk_yield), 2)", "SUM(m.milk_yield)");
        }
        return null;
    }

    private Metric metricOrDefault(String q) {
        Metric m = metric(q);
        return m != null ? m : new Metric("产奶量", "ROUND(SUM(m.milk_yield), 2)", "SUM(m.milk_yield)");
    }

    private Dim dim(String q) {
        if (containsAny(q, "牧场规模", "规模")) {
            return new Dim("牧场规模", "f.scale");
        }
        if (containsAny(q, "牧场", "农场", "基地")) {
            return new Dim("牧场", "f.farm_name");
        }
        if (containsAny(q, "地区", "区域", "大区")) {
            return new Dim("地区", "f.region");
        }
        if (containsAny(q, "品种", "牛种")) {
            return new Dim("品种", "c.breed");
        }
        if (containsAny(q, "泌乳阶段", "泌乳期")) {
            return new Dim("泌乳阶段", "m.lactation_stage");
        }
        if (containsAny(q, "牛舍", "栏舍", "牛栏")) {
            return new Dim("牛舍", "c.barn");
        }
        if (q.contains("胎次")) {
            return new Dim("胎次", "c.parity");
        }
        if (containsAny(q, "工作日", "周末", "星期", "周几")) {
            return new Dim("星期类型", "CASE WHEN DAYOFWEEK(m.record_date) IN (1,7) THEN '周末' ELSE '工作日' END");
        }
        if (q.contains("季度")) {
            return new Dim("季度", "CONCAT(YEAR(m.record_date), '-Q', QUARTER(m.record_date))");
        }
        return null;
    }

    private Dim dimOrDefault(String q) {
        Dim d = dim(q);
        return d != null ? d : new Dim("牧场", "f.farm_name");
    }

    private int topN(String q) {
        Matcher m = Pattern.compile("(?:top|TOP|Top)\\s*(\\d{1,2})").matcher(q);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("前\\s*(\\d{1,2})").matcher(q);
        if (m.find()) return Integer.parseInt(m.group(1));
        m = Pattern.compile("(\\d{1,2})\\s*[名个头栏栋]").matcher(q);
        if (m.find()) return Integer.parseInt(m.group(1));
        return 10;
    }

    /** 组合 WHERE:时间区间 + 维度值过滤 */
    private String whereOf(TimeRange tr, String filter) {
        String w = tr == null ? "" : tr.toSqlCondition("m.record_date");
        if (filter != null) {
            w = w.isEmpty() ? filter : w + " AND " + filter;
        }
        return w.isEmpty() ? "" : " WHERE " + w;
    }

    private TimeRange monthRange(LocalDate firstDay) {
        YearMonth ym = YearMonth.from(firstDay);
        return new TimeRange(ym.atDay(1), ym.plusMonths(1).atDay(1), ym.getYear() + "年" + ym.getMonthValue() + "月");
    }

    private TimeRange monthOffset(int minus) {
        return monthRange(LocalDate.now().minusMonths(minus).withDayOfMonth(1));
    }

    private boolean containsAny(String s, String... keys) {
        for (String k : keys) {
            if (s.contains(k)) return true;
        }
        return false;
    }
}
