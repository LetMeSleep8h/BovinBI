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
    model: str | None = None  # 按请求选模型(V4 Flash/V4 Pro/标准);空=环境默认


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
    # 探活:框架信息 + 底座 MCP 连通性(一眼看出跨语言链路是否通)
    import anyio

    async def _tools():
        return await agent._mcp_client().get_tools()

    try:
        tools = anyio.run(_tools)
        return {"status": "UP", "framework": "fastapi+langchain+langgraph",
                "javaMcp": "UP", "tools": [t.name for t in tools],
                "llm": f"configured:{agent.LLM_MODEL}" if agent.LLM_API_KEY else "offline"}
    except Exception as e:  # noqa: BLE001
        return {"status": "UP", "framework": "fastapi+langchain+langgraph",
                "javaMcp": "DOWN", "error": str(e)[:200],
                "llm": f"configured:{agent.LLM_MODEL}" if agent.LLM_API_KEY else "offline"}


@app.post("/v1/answer", response_model=AnswerResp)
def answer(req: AnswerReq):
    return agent.answer(req.datasetId, req.question, req.model)
