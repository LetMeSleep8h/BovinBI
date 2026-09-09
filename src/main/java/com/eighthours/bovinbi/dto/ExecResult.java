package com.eighthours.bovinbi.dto;

import java.util.LinkedHashMap;
import java.util.List;

/** 查询执行结果 */
public record ExecResult(List<ColInfo> columns, List<LinkedHashMap<String, Object>> rows,
                        int rowCount, long tookMs) {
}
