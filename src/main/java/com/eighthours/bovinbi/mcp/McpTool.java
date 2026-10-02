package com.eighthours.bovinbi.mcp;

import java.util.Map;

/**
 * 本地 MCP 工具 SPI:实现类注册进 {@link McpToolRegistry} 即同时获得两个身份 ——
 * 1) LangChain4j 工具循环里的能力(BovinTools 的 @Tool 方法委托到这里);
 * 2) 对外 MCP server 的一个 tools 条目(POST /mcp 可被任意 MCP 客户端调用)。
 * 安全边界内建在工具里(守护/白名单/预算),与传输协议无关:本地直调、HTTP、
 * 远程转发共用同一套防线 —— "提示词是建议,工具是法律"。
 */
public interface McpTool {

    McpToolDefinition definition();

    /** 执行;参数为 JSON 反序列化的 Map(字符串/数字),实现自行做类型与合法性校验 */
    McpToolResult execute(Map<String, Object> arguments);
}
