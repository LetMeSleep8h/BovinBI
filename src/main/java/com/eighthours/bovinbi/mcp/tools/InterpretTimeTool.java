package com.eighthours.bovinbi.mcp.tools;

import com.eighthours.bovinbi.mcp.McpTool;
import com.eighthours.bovinbi.mcp.McpToolDefinition;
import com.eighthours.bovinbi.mcp.McpToolResult;
import com.eighthours.bovinbi.service.TimeRange;
import com.eighthours.bovinbi.service.TimeRangeParser;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * interpretTime 工具:把自然语言时间表达解析为确定性的半开区间。
 * 场景:模型(或外部 MCP 客户端)写 SQL 前先调它拿准确的日期字面量,
 * 消除"上个月是哪个月"的猜算——对应评测已验证的"锚点口径"失败类:
 * LLM 自己算相对时间易错,交给确定性解析器后只需按返回的区间写 WHERE。
 * 无需 datasetId(时间解析与数据集无关),不消耗任何预算。
 */
@Component
@RequiredArgsConstructor
public class InterpretTimeTool implements McpTool {

    private final TimeRangeParser timeRangeParser;

    @Override
    public McpToolDefinition definition() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("expression", McpToolDefinition.property("string", "自然语言时间表达,如:上个月 / 今年 / 近30天 / 2027年3月 / 2026Q2"));
        return McpToolDefinition.of("interpretTime",
                "把自然语言时间表达解析为确定性半开区间 [start, end),返回可直接拼进 WHERE 的日期字面量与区间标签。"
                        + "写含时间的 SQL 前建议先调用,避免相对时间的口径猜算错误。",
                props, java.util.List.of("expression"));
    }

    @Override
    public McpToolResult execute(Map<String, Object> arguments) {
        String expression = arguments.get("expression") == null ? "" : String.valueOf(arguments.get("expression")).trim();
        if (expression.isEmpty()) {
            return McpToolResult.error("参数非法:expression 不能为空(可传问题原文,解析器自己找时间表达)");
        }
        TimeRange tr = timeRangeParser.parse(expression);
        if (tr == null) {
            return McpToolResult.text("未识别到明确时间表达,SQL 不应添加时间过滤(condition: NO_TIME)");
        }
        return McpToolResult.text(
                "标签: " + tr.label()
                        + "\n半开区间: start=" + TimeRange.F.format(tr.start())
                        + ", endExclusive=" + TimeRange.F.format(tr.endExclusive())
                        + "\nWHERE 写法: record_date >= '" + TimeRange.F.format(tr.start())
                        + "' AND record_date < '" + TimeRange.F.format(tr.endExclusive()) + "'");
    }
}
