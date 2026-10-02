package com.eighthours.bovinbi.mcp;

import java.util.List;

/**
 * MCP 工具执行结果(tools/call 的 result 形态):
 * content 为内容块列表(当前只产 text 块),isError=true 表示工具级失败
 * (守护拒绝/执行报错/预算拒绝),错误信息同样走 text 内容 —— "错误即数据",
 * 供模型阅读后自修复,与单 Agent 工具循环的约定一致。
 */
public record McpToolResult(List<Content> content, boolean isError) {

    /** MCP 内容块;type 当前固定 text,预留扩展(image/resource) */
    public record Content(String type, String text) {
        public static Content text(String text) {
            return new Content("text", text);
        }
    }

    public static McpToolResult text(String text) {
        return new McpToolResult(List.of(Content.text(text)), false);
    }

    public static McpToolResult error(String message) {
        return new McpToolResult(List.of(Content.text(message)), true);
    }

    /** 拼接全部 text 内容:LangChain4j @Tool 方法回喂模型用的就是这份文本 */
    public String text() {
        if (content == null || content.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (Content c : content) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append(c.text() == null ? "" : c.text());
        }
        return sb.toString();
    }
}
