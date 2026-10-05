# BovinBI Python Agent(AI 能力层)

Java 做底座、Python 做 AI 能力接入的第一版:提问时在前端自由选择
「Java 引擎」或「Python Agent」,两条链路独立、可对比评测。

## 架构原则

```
前端(选引擎) ──HTTP──▶ Java 底座(权限/会话/审计/守护)
                          │ engine=python 时转发
                          ▼
                    Python Agent(FastAPI @8090)
                          │ MCP 回环(JSON-RPC)
                          ▼
                    Java /mcp 工具(getSchema/executeSql)
```

- **Python 只做"智力"**(选表/生成 SQL),**执行和数据永远在 Java**:
  所有 SQL 经底座 SqlGuard(表白名单/仅 SELECT/强制 LIMIT),与 Java 引擎同一套边界;
- Python 服务**不连数据库、不持有凭据**,崩溃不影响底座;
- 无 `LLM_API_KEY` 时自动降级为内置离线规则模式,零外部依赖可演示;
  配置 Key 后升级为 OpenAI 兼容 LLM 生成(DeepSeek/GLM/通义均可)。

## 本地运行

```bash
# 前提:Java 栈已起(./docker/start-all.sh,/mcp 已开启)
pip install -r requirements.txt
JAVA_MCP_URL=http://localhost:8080/mcp uvicorn app.main:app --port 8090
# 探活(顺带检查到 Java MCP 的连通性)
curl http://localhost:8090/health
```

## 环境 变量

| 变量 | 默认 | 说明 |
|---|---|---|
| JAVA_MCP_URL | http://localhost:8080/mcp | Java 底座 MCP 端点(compose 内为 http://backend:8080/mcp) |
| MCP_API_KEY | bovin-mcp-demo | 与 bovin.mcp.api-keys 一致 |
| LLM_API_KEY | (空) | 配置后启用 LLM 生成;留空走离线规则 |
| LLM_BASE_URL | https://api.deepseek.com | OpenAI 兼容端点 |
| LLM_MODEL | deepseek-chat | 模型名 |
