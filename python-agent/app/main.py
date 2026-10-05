"""Python Agent HTTP 服务(FastAPI)。

Java 底座把"engine=python"的提问路由到这里;本服务的所有数据操作
经 MCP 回环到 Java /mcp(取 Schema/执行 SQL),自身不连数据库。

启动: uvicorn app.main:app --host 0.0.0.0 --port 8090
环境: JAVA_MCP_URL(默认 http://localhost:8080/mcp)、MCP_API_KEY、
      LLM_API_KEY/LLM_BASE_URL/LLM_MODEL(配置后从离线规则升级为 LLM 生成)
"""

from fastapi import FastAPI
from pydantic import BaseModel

from . import agent, mcp_client

app = FastAPI(title="BovinBI Python Agent", version="1.0.0")


class AnswerReq(BaseModel):
    datasetId: int
    question: str
    sessionId: int | None = None


class AnswerResp(BaseModel):
    sql: str | None
    explanation: str | None
    columns: list
    rows: list
    rowCount: int
    fallback: bool
    fallbackHint: str | None
    engine: str
    steps: list
    tookMs: int


@app.get("/health")
def health():
    # 探活顺带 ping 底座 MCP:一眼看出跨语言链路是否通
    try:
        tools = mcp_client.list_tools()
        return {"status": "UP", "javaMcp": "UP", "tools": len(tools),
                "llm": "configured" if agent.LLM_API_KEY else "offline-rule"}
    except Exception as e:  # noqa: BLE001
        return {"status": "UP", "javaMcp": "DOWN", "error": str(e)[:200],
                "llm": "configured" if agent.LLM_API_KEY else "offline-rule"}


@app.post("/v1/answer", response_model=AnswerResp)
def answer(req: AnswerReq):
    return agent.answer(req.datasetId, req.question)
