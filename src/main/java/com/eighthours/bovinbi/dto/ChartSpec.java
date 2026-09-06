package com.eighthours.bovinbi.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/** 图表规格:由后端根据结果形态自动推荐,前端直接渲染 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChartSpec {
    /** line / bar / pie / none */
    private String type;
    private String xAxisName;
    private List<String> xValues;
    private List<Series> series;
    /** 推荐理由,展示给用户 */
    private String reason;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Series {
        private String name;
        private List<Double> values;
    }
}
