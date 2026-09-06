package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.dto.ChartSpec;
import com.eighthours.bovinbi.dto.ColInfo;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.regex.Pattern;

/**
 * 智能图表推荐:基于"结果形态 + 问题意图"两路信号自动选择可视化类型,
 * 并支持把 按月×品类 的长表结果透视成多序列折线图。
 */
@Service
public class ChartAdvisor {

    private static final Pattern TIME_NAME =
            Pattern.compile("(?i)(date|日期|月份|month|day|week|周|季度|quarter|期间|年份)");
    private static final Pattern RATIO_INTENT = Pattern.compile("占比|份额|构成|分布|结构|比例");
    private static final DateTimeFormatter[] DATE_FORMATS = {
            DateTimeFormatter.ofPattern("yyyy-MM-dd"), DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyy-MM") };

    public ChartSpec advise(String question, List<ColInfo> cols, List<LinkedHashMap<String, Object>> rows) {
        if (rows == null || rows.isEmpty() || cols == null || cols.isEmpty()) {
            return new ChartSpec("none", null, null, null, "无数据可绘制");
        }
        int n = cols.size();
        int timeIdx = -1, catIdx = -1;
        List<Integer> numIdx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            ColInfo c = cols.get(i);
            if (isNumericType(c) || isNumericValues(rows, i)) {
                numIdx.add(i);
            } else if (timeIdx < 0 && looksLikeTime(c, rows, i)) {
                timeIdx = i;
            } else if (catIdx < 0) {
                catIdx = i;
            }
        }

        String q = question == null ? "" : question;

        // 单行单指标 → 大数字卡片
        if (rows.size() == 1 && numIdx.size() == 1 && timeIdx < 0 && catIdx < 0) {
            return new ChartSpec("none", null, null, null, "单值指标,以指标卡呈现");
        }

        // 3 列: 时间 × 类别 × 数值 → 透视成多序列折线/柱状
        if (timeIdx >= 0 && catIdx >= 0 && !numIdx.isEmpty() && n >= 3) {
            return pivot(rows, timeIdx, catIdx, numIdx.get(0), cols, q);
        }

        // 时间序列 → 折线
        if (timeIdx >= 0 && !numIdx.isEmpty()) {
            final int ti = timeIdx;
            List<String> x = rows.stream().map(r -> str(r, ti)).toList();
            List<ChartSpec.Series> series = new ArrayList<>();
            int limit = Math.min(numIdx.size(), 3);
            for (int k = 0; k < limit; k++) {
                int idx = numIdx.get(k);
                series.add(new ChartSpec.Series(cols.get(idx).name(),
                        rows.stream().map(r -> num(r, idx)).toList()));
            }
            return new ChartSpec("line", cols.get(timeIdx).name(), x, series, "X轴为时间维度,折线图最利于观察趋势");
        }

        // 类别 + 数值
        if (catIdx >= 0 && !numIdx.isEmpty()) {
            final int ci = catIdx;
            String cat = cols.get(ci).name();
            List<String> x = rows.stream().map(r -> str(r, ci)).toList();
            int vIdx = numIdx.get(0);
            List<Double> vals = rows.stream().map(r -> num(r, vIdx)).toList();

            boolean ratioAsked = RATIO_INTENT.matcher(q).find();
            if (ratioAsked && rows.size() >= 2 && rows.size() <= 8 && allPositive(vals)) {
                return new ChartSpec("pie", cat, x,
                        List.of(new ChartSpec.Series(cols.get(vIdx).name(), vals)), "问题关心构成且类别≤8,饼图直观");
            }
            boolean horizontal = rows.size() > 12 || avgLen(x) > 6;
            List<ChartSpec.Series> series = new ArrayList<>();
            int limit = Math.min(numIdx.size(), 3);
            for (int k = 0; k < limit; k++) {
                int idx = numIdx.get(k);
                series.add(new ChartSpec.Series(cols.get(idx).name(),
                        rows.stream().map(r -> num(r, idx)).toList()));
            }
            return new ChartSpec(horizontal ? "barH" : "bar", cat, x, series,
                    "类别型对比," + (horizontal ? "标签较长采用横向条形图" : "采用柱状图"));
        }

        return new ChartSpec("none", null, null, null, "结果形态复杂,以表格呈现");
    }

    /** 长表(时间,类别,数值) → 宽表多序列 */
    private ChartSpec pivot(List<LinkedHashMap<String, Object>> rows, int tIdx, int cIdx, int vIdx,
                            List<ColInfo> cols, String question) {
        TreeSet<String> xSet = new TreeSet<>();
        Map<String, Map<String, Double>> matrix = new LinkedHashMap<>();
        for (LinkedHashMap<String, Object> r : rows) {
            String t = str(r, tIdx);
            String c = str(r, cIdx);
            xSet.add(t);
            matrix.computeIfAbsent(c, k -> new LinkedHashMap<>()).put(t, num(r, vIdx));
        }
        List<String> x = new ArrayList<>(xSet);
        List<ChartSpec.Series> series = new ArrayList<>();
        int limit = Math.min(matrix.size(), 8);
        int k = 0;
        for (Map.Entry<String, Map<String, Double>> e : matrix.entrySet()) {
            if (k++ >= limit) break;
            List<Double> vals = new ArrayList<>();
            for (String t : x) {
                vals.add(e.getValue().getOrDefault(t, 0.0));
            }
            series.add(new ChartSpec.Series(e.getKey(), vals));
        }
        if (series.size() > 1) {
            return new ChartSpec("line", cols.get(tIdx).name(), x, series,
                    "检测到 时间×" + cols.get(cIdx).name() + " 交叉数据,已透视成多序列对比");
        }
        return new ChartSpec("bar", cols.get(tIdx).name(), x, series, "时间×类别数据透视");
    }

    private boolean isNumericType(ColInfo c) {
        if (c == null || c.type() == null) return false;
        String t = c.type().toUpperCase();
        return t.contains("INT") || t.contains("DECIMAL") || t.contains("NUMERIC")
                || t.contains("DOUBLE") || t.contains("FLOAT") || t.contains("REAL") || t.contains("NUMBER");
    }

    private boolean isNumericValues(List<LinkedHashMap<String, Object>> rows, int idx) {
        int hit = 0;
        for (int i = 0; i < Math.min(rows.size(), 10); i++) {
            Object v = values(rows.get(i)).get(idx);
            if (v instanceof Number) hit++;
        }
        return hit > 0;
    }

    private boolean looksLikeTime(ColInfo c, List<LinkedHashMap<String, Object>> rows, int idx) {
        String name = c == null ? null : c.name();
        if (name != null && TIME_NAME.matcher(name).find()) return true;
        int hit = 0;
        for (int i = 0; i < Math.min(rows.size(), 5); i++) {
            Object v = values(rows.get(i)).get(idx);
            if (v instanceof String s && isDateLike(s)) hit++;
        }
        return hit >= 2;
    }

    private boolean isDateLike(String s) {
        if (s.matches("\\d{4}-\\d{2}")) return true;
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                LocalDate.parse(s, f);
                return true;
            } catch (DateTimeParseException ignore) {
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private List<Object> values(LinkedHashMap<String, Object> row) {
        return new ArrayList<>(row.values());
    }

    private String str(LinkedHashMap<String, Object> row, int idx) {
        Object v = values(row).get(idx);
        return v == null ? "" : String.valueOf(v);
    }

    private Double num(LinkedHashMap<String, Object> row, int idx) {
        Object v = values(row).get(idx);
        if (v instanceof Number nb) return nb.doubleValue();
        if (v instanceof String s) {
            try {
                return Double.parseDouble(s.replace(",", "").replace("%", ""));
            } catch (Exception ignore) {
                return 0.0;
            }
        }
        return 0.0;
    }

    private boolean allPositive(List<Double> vals) {
        return vals.stream().allMatch(v -> v != null && v > 0);
    }

    private double avgLen(List<String> xs) {
        return xs.stream().mapToInt(String::length).average().orElse(0);
    }
}
