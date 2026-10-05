"""Java 底座 MCP 客户端(JSON-RPC 2.0 over HTTP)。

Python Agent 的"手"全部伸回 Java:取 Schema、执行 SQL 都走底座的 /mcp 端点,
安全边界(守护/表白名单/强制 LIMIT)因此全部保留在 Java 侧 —— Python 不直连数据库。
"""

import os
import itertools
import httpx

JAVA_MCP_URL = os.environ.get("JAVA_MCP_URL", "http://localhost:8080/mcp")
MCP_API_KEY = os.environ.get("MCP_API_KEY", "bovin-mcp-demo")

_ids = itertools.count(1)


class McpError(RuntimeError):
    """MCP 调用失败(工具返回 isError 或协议层错误)"""


def _rpc(method: str, params: dict) -> dict:
    body = {"jsonrpc": "2.0", "id": next(_ids), "method": method, "params": params}
    headers = {"Content-Type": "application/json", "Accept": "application/json"}
    if MCP_API_KEY:
        headers["Authorization"] = f"Bearer {MCP_API_KEY}"
    resp = httpx.post(JAVA_MCP_URL, json=body, headers=headers, timeout=60)
    resp.raise_for_status()
    data = resp.json()
    if "error" in data:
        raise McpError(f"MCP {method} 错误: {data['error'].get('message')}")
    return data.get("result", {})


def list_tools() -> list:
    return _rpc("tools/list", {}).get("tools", [])


def call_tool(name: str, arguments: dict) -> str:
    """调用工具并返回拼接文本;isError 时抛 McpError(错误即数据,交给上层降级)"""
    result = _rpc("tools/call", {"name": name, "arguments": arguments})
    text = "\n".join(c.get("text", "") for c in result.get("content", []))
    if result.get("isError"):
        raise McpError(text or f"工具 {name} 执行失败")
    return text


def get_schema(dataset_id: int, keywords: str = "") -> str:
    return call_tool("getSchema", {"datasetId": dataset_id, "keywords": keywords})


def execute_sql(dataset_id: int, sql: str) -> str:
    """返回执行预览文本(执行成功:共 N 行 / 列: ... / 前 N 行)"""
    return call_tool("executeSql", {"datasetId": dataset_id, "sql": sql})
