package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.common.BizException;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则式中文时间解析器(确定性,可单测)。
 * 支持:今年/去年/今年Q1/上个月/本月/上季度/近N天/近N个月/最近一周/2025年/2025年3月/2025年第二季度
 */
@Component
public class TimeRangeParser {

    private static final Pattern P_YEAR = Pattern.compile("(\\d{4})年");
    private static final Pattern P_YEAR_MONTH = Pattern.compile("(\\d{4})年(\\d{1,2})月");
    private static final Pattern P_MONTH_ONLY = Pattern.compile("(?<![\\d年])(\\d{1,2})月");
    private static final Pattern P_QUARTER = Pattern.compile("(\\d{4})年第?([一二三四1-4])季度");
    private static final Pattern P_LAST_N_DAYS = Pattern.compile("近(\\d{1,3})天|最近(\\d{1,3})天");
    private static final Pattern P_LAST_N_MONTHS = Pattern.compile("近(\\d{1,2})个月|最近(\\d{1,2})个月");
    private static final Pattern P_QUARTER_CN = Pattern.compile("今年第?([一二三四1-4])季度|今年Q([1-4])|Q([1-4])");

    private final LocalDate today;

    public TimeRangeParser() {
        this(LocalDate.now());
    }

    /** 支持注入固定日期,保证评测可复现 */
    public TimeRangeParser(LocalDate today) {
        this.today = today;
    }

    public TimeRange parse(String question) {
        String q = question == null ? "" : question.trim();

        Matcher m = P_LAST_N_DAYS.matcher(q);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
            return new TimeRange(today.minusDays(n - 1L), today.plusDays(1), "近" + n + "天");
        }
        m = P_LAST_N_MONTHS.matcher(q);
        if (m.find()) {
            int n = Integer.parseInt(m.group(1));
            return new TimeRange(today.minusMonths(n - 1L).withDayOfMonth(1), today.plusDays(1), "近" + n + "个月");
        }
        if (q.contains("最近一周") || q.contains("近一周") || q.contains("本周") || q.contains("这周")) {
            return new TimeRange(today.minusDays(6), today.plusDays(1), "最近一周");
        }
        if (q.contains("上季度") || q.contains("上个季度")) {
            int curQ = (today.getMonthValue() - 1) / 3;
            LocalDate qStart = LocalDate.of(today.getYear(), curQ * 3 + 1, 1).minusMonths(3);
            YearMonth ym = YearMonth.from(qStart);
            return new TimeRange(qStart, qStart.plusMonths(3), ym.getYear() + "年Q" + (curQ == 0 ? 4 : curQ));
        }
        if (q.contains("本季度") || q.contains("这个季度")) {
            int startMonth = (today.getMonthValue() - 1) / 3 * 3 + 1;
            LocalDate s = LocalDate.of(today.getYear(), startMonth, 1);
            return new TimeRange(s, s.plusMonths(3), today.getYear() + "年Q" + ((startMonth - 1) / 3 + 1));
        }
        m = P_QUARTER.matcher(q);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1));
            int quarter = quarterOf(m.group(2));
            LocalDate s = LocalDate.of(year, (quarter - 1) * 3 + 1, 1);
            return new TimeRange(s, s.plusMonths(3), year + "年Q" + quarter);
        }
        m = P_QUARTER_CN.matcher(q);
        if (m.find()) {
            String g = m.group(1) != null ? m.group(1) : m.group(2) != null ? m.group(2) : m.group(3);
            int quarter = quarterOf(g);
            LocalDate s = LocalDate.of(today.getYear(), (quarter - 1) * 3 + 1, 1);
            return new TimeRange(s, s.plusMonths(3), today.getYear() + "年Q" + quarter);
        }
        m = P_YEAR_MONTH.matcher(q);
        if (m.find()) {
            int year = Integer.parseInt(m.group(1));
            int month = Integer.parseInt(m.group(2));
            checkMonth(month);
            YearMonth ym = YearMonth.of(year, month);
            return new TimeRange(ym.atDay(1), ym.plusMonths(1).atDay(1), year + "年" + month + "月");
        }
        if (q.contains("上个月") || q.contains("上月")) {
            YearMonth ym = YearMonth.from(today).minusMonths(1);
            return new TimeRange(ym.atDay(1), ym.plusMonths(1).atDay(1), ym.getYear() + "年" + ym.getMonthValue() + "月");
        }
        if (q.contains("本月") || q.contains("这个月")) {
            YearMonth ym = YearMonth.from(today);
            return new TimeRange(ym.atDay(1), ym.plusMonths(1).atDay(1), ym.getYear() + "年" + ym.getMonthValue() + "月");
        }
        if (q.contains("去年") || q.contains("上一年")) {
            int y = today.getYear() - 1;
            return new TimeRange(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1), y + "年");
        }
        m = P_YEAR.matcher(q);
        if (m.find()) {
            int y = Integer.parseInt(m.group(1));
            return new TimeRange(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1), y + "年");
        }
        if (q.contains("今年") || q.contains("这年")) {
            int y = today.getYear();
            return new TimeRange(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1), y + "年");
        }
        m = P_MONTH_ONLY.matcher(q);
        if (m.find()) {
            int month = Integer.parseInt(m.group(1));
            checkMonth(month);
            int year = today.getYear();
            if (month > today.getMonthValue()) {
                year -= 1; // "3月"在9月提问 → 理解为今年3月已过,未到的月份理解为去年(就近原则)
            }
            YearMonth ym = YearMonth.of(year, month);
            return new TimeRange(ym.atDay(1), ym.plusMonths(1).atDay(1), year + "年" + month + "月");
        }
        return null; // 未识别时间 → 由管线决定默认范围
    }

    private int quarterOf(String s) {
        return switch (s) {
            case "一", "1" -> 1;
            case "二", "2" -> 2;
            case "三", "3" -> 3;
            case "四", "4" -> 4;
            default -> throw new BizException("无法识别的季度: " + s);
        };
    }

    private void checkMonth(int month) {
        if (month < 1 || month > 12) {
            throw new BizException("无法识别的月份: " + month);
        }
    }
}
