package com.eighthours.bovinbi.evaluation;

import com.eighthours.bovinbi.dto.ChartSpec;
import com.eighthours.bovinbi.dto.ColInfo;
import com.eighthours.bovinbi.service.ChartAdvisor;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** 图表推荐单测:结果形态 + 问题意图 双信号 */
class ChartAdvisorTest {

    private final ChartAdvisor advisor = new ChartAdvisor();

    private LinkedHashMap<String, Object> row(Object... kv) {
        LinkedHashMap<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void singleValueBecomesNone() {
        List<ColInfo> cols = List.of(new ColInfo("产奶量", "DECIMAL"));
        List<LinkedHashMap<String, Object>> rows = List.of(row("产奶量", 903202.26));
        ChartSpec s = advisor.advise("今年总产奶量", cols, new ArrayList<>(rows));
        assertEquals("none", s.getType(), "单行单指标应为指标卡而非图表");
    }

    @Test
    void timeSeriesBecomesLine() {
        List<ColInfo> cols = List.of(new ColInfo("月份", "VARCHAR"), new ColInfo("产奶量", "DECIMAL"));
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            rows.add(row("月份", "2026-%02d".formatted(i), "产奶量", 1000.0 * i));
        }
        ChartSpec s = advisor.advise("每月产奶量趋势", cols, rows);
        assertEquals("line", s.getType());
        assertEquals("月份", s.getXAxisName());
        assertEquals(12, s.getXValues().size());
        assertEquals(1, s.getSeries().size());
    }

    @Test
    void ratioIntentWithFewCategoriesBecomesPie() {
        List<ColInfo> cols = List.of(new ColInfo("品种", "VARCHAR"), new ColInfo("产奶量", "DECIMAL"));
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        String[] cats = {"荷斯坦", "西门塔尔", "娟姗"};
        for (int i = 0; i < cats.length; i++) {
            rows.add(row("品种", cats[i], "产奶量", 100.0 * (i + 1)));
        }
        ChartSpec s = advisor.advise("各品种产奶量占比", cols, rows);
        assertEquals("pie", s.getType(), "占比意图+≤8类+全正数应推荐饼图");
    }

    @Test
    void manyCategoriesWithoutRatioIntentBecomesBar() {
        List<ColInfo> cols = List.of(new ColInfo("品种", "VARCHAR"), new ColInfo("产奶量", "DECIMAL"));
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            rows.add(row("品种", "品种" + i, "产奶量", 100.0 * (i + 1)));
        }
        ChartSpec s = advisor.advise("各品种产奶量", cols, rows);
        assertEquals("bar", s.getType(), "10类且无占比意图应为柱状图而非饼图");
    }

    @Test
    void longLabelsBecomeHorizontalBar() {
        List<ColInfo> cols = List.of(new ColInfo("牧场", "VARCHAR"), new ColInfo("产奶量", "DECIMAL"));
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            rows.add(row("牧场", "呼和浩特金山牧场第" + i + "分场", "产奶量", 100.0 * (i + 1)));
        }
        ChartSpec s = advisor.advise("产奶量Top5牧场", cols, rows);
        assertEquals("barH", s.getType(), "长标签应转横向条形图");
    }

    @Test
    void longFormatPivotToMultiSeries() {
        List<ColInfo> cols = List.of(new ColInfo("月份", "VARCHAR"), new ColInfo("品种", "VARCHAR"),
                new ColInfo("产奶量", "DECIMAL"));
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        rows.add(row("月份", "2026-07", "品种", "荷斯坦", "产奶量", 100.0));
        rows.add(row("月份", "2026-07", "品种", "娟姗", "产奶量", 200.0));
        rows.add(row("月份", "2026-08", "品种", "荷斯坦", "产奶量", 150.0));
        rows.add(row("月份", "2026-08", "品种", "娟姗", "产奶量", 250.0));
        ChartSpec s = advisor.advise("近3个月每月各品种产奶量", cols, rows);
        assertEquals("line", s.getType(), "时间×类别长表应透视成多序列");
        assertEquals(2, s.getSeries().size());
        assertEquals(List.of("2026-07", "2026-08"), s.getXValues(), "透视后X轴应按时间升序去重");
        assertEquals(2, s.getSeries().get(0).getValues().size(), "每个系列应对齐时间轴并补零");
    }

    @Test
    void emptyRowsIsNone() {
        ChartSpec s = advisor.advise("任意问题", List.of(new ColInfo("a", "INT")), new ArrayList<>());
        assertEquals("none", s.getType());
    }
}
