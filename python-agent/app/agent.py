"""LangGraph 工作流:意图路由 → ReAct Agent(MCP 工具)→ 组装回答。

图结构(StateGraph):
    START → intent(意图判定,LLM 优先) ─┬─ chitchat(直答)
                                        └─ agent(ReAct 循环,工具=MCP) → END
                                        └─ fallback(离线提示)
    - agent 节点用 create_react_agent:模型自主决定调哪些 MCP 工具、循环至产出答案,
      框架管理消息状态与工具调用循环(不再手写循环);
    - 工具经 langchain-mcp-adapters 直连 Java 底座 /mcp(streamable-http),
      守护/表白名单仍在 Java 侧 —— 安全边界不因框架重构外流;
    - 节点异常 → fallback 兜底回答(错误进状态,不向上抛断链)。
"""

import json
import os
import re
import time
from typing import Annotated, Literal, Optional, TypedDict

import anyio
from langchain_core.messages import AIMessage, HumanMessage, SystemMessage
from langchain_mcp_adapters.client import MultiServerMCPClient
from langchain_openai import ChatOpenAI
from langgraph.graph import StateGraph, START, END
from langgraph.prebuilt import create_react_agent

JAVA_MCP_URL = os.environ.get("JAVA_MCP_URL", "http://localhost:8080/mcp")
MCP_API_KEY = os.environ.get("MCP_API_KEY", "bovin-mcp-demo")
LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "https://api.deepseek.com")
LLM_API_KEY = os.environ.get("LLM_API_KEY", "")
LLM_MODEL = os.environ.get("LLM_MODEL", "deepseek-chat")

INTENT_SYSTEM = (
    "你是问答系统的意图判别器。判断用户输入是闲聊还是取数:\n"
    "- 闲聊:问候、道谢、告别、询问你的身份/能力/用法(如 你能干什么/你是谁/谢谢/在么),"
    "或任何不需要查询数据仓库就能回答的话;\n"
    "- 取数:任何需要查数据才能回答的问题(哪怕写得含糊,如 销售额情况/看看牧场)。\n"
    "只输出一个词:CHIT_CHAT 或 QUERY。"
)

AGENT_SYSTEM = (
    "你是 BovinBI 数据分析 Agent,通过工具回答数仓业务问题。\n"
    "规则:1) 先调 getSchema 了解表结构再写 SQL(可传问题关键词);\n"
    "2) SQL 只用 Schema 中的表和列,末尾必须有 LIMIT,输出列用中文别名;\n"
    "3) 用 executeSql 执行拿到结果后,在最后一行输出 JSON:"
    '{"sql": "<已成功执行的SQL>", "explanation": "一句话中文结论"};\n'
    "4) 用户的问题不是数据问题时,直接简短回答,不调用工具。"
)

CHAT_SYSTEM = (
    "你是 BovinBI 里的数据助手,用户此刻在和你闲聊。要求:\n"
    "- 像朋友一样自然回应:可以共情、可以幽默,每轮 2~3 句话以内,口语化,别堆客套话、别用列表;\n"
    "- 你的绝活是把一句话变成 SQL 并出图表(电商零售/牧场养殖/全球牛奶产量三个数据集);\n"
    "- 察觉用户其实想查数据时,自然地给一个能直接问的例子(如\'销售额Top10商品类目\');\n"
    "- 不编造自己没有的能力,不聊与工作无关的敏感话题。"
)

CHIT_CHAT_ANSWER = (
    "我是 BovinBI 的 Python Agent(LangGraph 驱动):把一句自然语言变成 SQL 并执行出图表,"
    "支持趋势/TopN/占比/分组/单值指标。试试:销售额Top10商品类目 / 各客户州销售额。"
)


_LLM_CACHE: dict[str, ChatOpenAI] = {}


def _llm(model: Optional[str] = None) -> Optional[ChatOpenAI]:
    """按模型名缓存实例(V4 Flash/V4 Pro/标准…);未指定回落 LLM_MODEL 环境默认。
    离线(无 Key)返回 None,图退化为闲聊关键词 + 兜底提示。"""
    if not LLM_API_KEY:
        return None
    name = (model or LLM_MODEL).strip() or LLM_MODEL
    if name not in _LLM_CACHE:
        _LLM_CACHE[name] = ChatOpenAI(
            base_url=LLM_BASE_URL.rstrip("/"),
            api_key=LLM_API_KEY,
            model=name,
            temperature=0.0,
            timeout=90,
            max_retries=1,
        )
    return _LLM_CACHE[name]


def _mcp_client() -> MultiServerMCPClient:
    """Java 底座 MCP 客户端(streamable-http);工具加载后交给 ReAct Agent 绑定"""
    return MultiServerMCPClient({
        "bovinbi": {
            "url": JAVA_MCP_URL,
            "transport": "streamable_http",
            "headers": {"Authorization": f"Bearer {MCP_API_KEY}"},
        }
    })


class AgentState(TypedDict):
    """图的状态:问题 + 模型 + 引擎结果字段(与 Java AnswerPayload 对齐)+ 步骤轨迹"""
    question: str
    dataset_id: int
    model: str | None
    sql: Optional[str]
    explanation: Optional[str]
    columns: list
    rows: list
    row_count: int
    fallback: bool
    fallback_hint: Optional[str]
    steps: Annotated[list, lambda a, b: (a or []) + (b or [])]


# ---------------- 节点 ----------------

def intent_node(state: AgentState) -> dict:
    """意图判定:LLM 一个词的判决;失败/离线退回关键词"""
    q = state["question"].strip()
    llm = _llm(state.get("model"))
    if llm:
        try:
            verdict = llm.invoke([SystemMessage(content=INTENT_SYSTEM),
                                  HumanMessage(content=q)]).content.strip().upper()
            if "CHIT_CHAT" in verdict:
                return {"steps": [{"name": "意图(LLM)", "detail": "闲聊 → 直答", "ok": True}]}
            if "QUERY" in verdict:
                return {"steps": [{"name": "意图(LLM)", "detail": "取数 → 进入 Agent", "ok": True}]}
        except Exception as e:  # noqa: BLE001
            return {"steps": [{"name": "意图(LLM失败,退关键词)", "detail": str(e)[:120], "ok": False}]}
    hit = _keyword_chitchat(q)
    return {"steps": [{"name": "意图(关键词)", "detail": "闲聊" if hit else "取数", "ok": True}]}


_CHIT_WORDS = ("你是谁 你叫什么 你能做什么 你能干什么 你会做什么 你会干什么 你有什么功能 有什么功能 "
               "能干什么 会什么 你是干嘛的 你好 您好 哈喽 hello hi 在吗 谢谢 多谢 感谢 再见 拜拜")


def _keyword_chitchat(q: str) -> bool:
    return len(q.strip()) <= 40 and any(p in q.lower() for p in _CHIT_WORDS.split())


def route_after_intent(state: AgentState) -> Literal["chitchat", "agent", "fallback"]:
    """路由:按意图节点的判定 + LLM 可用性分流"""
    if not _llm(state.get("model")):
        steps = state.get("steps") or []
        if steps and "闲聊" in (steps[-1].get("detail") or ""):
            return "chitchat"
        return "fallback"  # 离线且是取数:无 LLM 无法规划,兜底提示
    steps = state.get("steps") or []
    detail = (steps[-1].get("detail") or "") if steps else ""
    if "闲聊" in detail:
        return "chitchat"
    return "agent"


def chitchat_node(state: AgentState) -> dict:
    """三角色 · 角色二(闲聊 AI):LLM 自由对话;离线/失败回落固定文案"""
    llm = _llm(state.get("model"))
    if llm:
        try:
            reply = llm.invoke([SystemMessage(content=CHAT_SYSTEM),
                                HumanMessage(content=state["question"])]).content
            reply = (reply or "").strip()
            if reply:
                return {"explanation": reply, "row_count": 0, "fallback": False,
                        "steps": [{"name": "闲聊AI", "detail": "对话式回答", "ok": True}]}
        except Exception:  # noqa: BLE001
            pass  # LLM 失败回落固定文案(离线兜底)
    q = state["question"]
    if any(k in q for k in ("谢谢", "多谢", "感谢")):
        text = "不客气!还想看什么数据,直接问就行。"
    elif any(k in q for k in ("再见", "拜拜")):
        text = "再见!数据随时在这里等你。"
    else:
        text = CHIT_CHAT_ANSWER
    return {"explanation": text, "row_count": 0, "fallback": False,
            "steps": [{"name": "闲聊直答", "detail": "离线固定文案", "ok": True}]}


def _extract_json(text: str) -> Optional[dict]:
    m = re.search(r"\{.*\}", text or "", re.DOTALL)
    if not m:
        return None
    try:
        return json.loads(m.group(0))
    except Exception:  # noqa: BLE001
        return None


def _parse_preview(text: str) -> tuple[list, list]:
    """解析 executeSql 工具返回的预览文本 → (columns, rows)"""
    columns, rows = [], []
    for line in (text or "").splitlines():
        line = line.strip()
        if line.startswith("列:"):
            names = [c.strip() for c in line[2:].split("|")]
            columns = [{"name": n, "type": "VARCHAR"} for n in names if n]
        elif not columns or any(k in line for k in ("守护拒绝", "执行失败", "行:")) or re.match(r"^前 \d+ 行", line):
            continue
        else:
            vals = [v.strip() for v in line.split("|")]
            if len(vals) == len(columns):
                rows.append({c["name"]: _num(vals[i]) for i, c in enumerate(columns)})
    return columns, rows


def _num(v: str):
    """'1233131.72' → float;纯数字转数值,图表推荐才能识别数值列"""
    try:
        return int(v)
    except ValueError:
        try:
            return float(v)
        except ValueError:
            return v


def _run_async(coro):
    """ReAct 循环是 async(LangGraph/MCP 均异步);FastAPI 的线程池同步端点里用 anyio 桥接"""
    return anyio.run(coro)


def agent_node(state: AgentState) -> dict:
    """ReAct Agent 节点:LLM + MCP 工具循环(框架驱动),从最终消息解析 SQL 与结论"""
    llm = _llm(state.get("model"))
    started = time.time()

    async def _run():
        client = _mcp_client()
        tools = await client.get_tools()
        agent = create_react_agent(llm, tools, prompt=AGENT_SYSTEM)
        user_msg = (
            f"数据集ID: {state['dataset_id']}\n问题: {state['question']}\n"
            '回答要求:通过 executeSql 成功执行后,最后一行输出 JSON '
            '{"sql": "...", "explanation": "一句话中文结论"}'
        )
        return await agent.ainvoke(
            {"messages": [HumanMessage(content=user_msg)]},
            config={"recursion_limit": 30},
        )

    result = _run_async(_run)
    messages = result.get("messages", [])
    tool_calls = sum(1 for m in messages if isinstance(m, AIMessage) and m.tool_calls)
    steps = [{"name": "ReAct循环", "detail": f"{len(messages)} 条消息 / 工具调用 {tool_calls} 次 / "
              f"{int((time.time() - started) * 1000)}ms", "ok": True}]

    final_sql, final_exp = None, ""
    for m in reversed(messages):
        if isinstance(m, AIMessage) and m.content and not m.tool_calls:
            content = m.content if isinstance(m.content, str) else str(m.content)
            data = _extract_json(content)
            if data and data.get("sql"):
                final_sql, final_exp = data["sql"].strip(), data.get("explanation", "")
                break
            if not final_exp:
                final_exp = content[:200]
    if not final_sql:
        return {"fallback": True, "fallback_hint": "Agent 未能产出 SQL(可换个问法重试)",
                "explanation": final_exp, "steps": steps}

    # 生成的 SQL 交底座执行(与循环同一 MCP 通道;守护在 Java 侧)
    async def _exec():
        client = _mcp_client()
        tools = await client.get_tools()
        ex = next(t for t in tools if t.name == "executeSql")
        return await ex.ainvoke({"datasetId": state["dataset_id"], "sql": final_sql})

    preview = _run_async(_exec)
    columns, rows = _parse_preview(preview)
    ok = "执行成功" in (preview or "")
    steps.append({"name": "executeSql(MCP)", "detail": (preview or "")[:120], "ok": ok})
    if not ok:
        return {"fallback": True, "fallback_hint": f"执行失败: {(preview or '')[:150]}",
                "sql": final_sql, "steps": steps}
    return {"sql": final_sql, "explanation": final_exp, "columns": columns, "rows": rows,
            "row_count": len(rows), "fallback": False, "steps": steps}


def fallback_node(state: AgentState) -> dict:
    offline = not _llm(state.get("model"))
    hint = ("Python Agent 离线模式仅支持闲聊;配置 LLM_API_KEY 后即可完整回答取数问题"
            if offline else "未能理解或执行该问题,可换个问法重试")
    return {"fallback": True, "fallback_hint": hint, "row_count": 0,
            "steps": [{"name": "兜底", "detail": hint, "ok": False}]}


# ---------------- 组图 ----------------

def build_graph():
    g = StateGraph(AgentState)
    g.add_node("intent", intent_node)
    g.add_node("chitchat", chitchat_node)
    g.add_node("agent", agent_node)
    g.add_node("fallback", fallback_node)
    g.add_edge(START, "intent")
    g.add_conditional_edges("intent", route_after_intent,
                            {"chitchat": "chitchat", "agent": "agent", "fallback": "fallback"})
    g.add_edge("chitchat", END)
    g.add_edge("agent", END)
    g.add_edge("fallback", END)
    return g.compile()


GRAPH = build_graph()


def answer(dataset_id: int, question: str, model: str | None = None) -> dict:
    """入口:跑图,把状态映射为与 Java AnswerPayload 同构的字典"""
    started = time.time()
    try:
        final = GRAPH.invoke({"question": question, "dataset_id": dataset_id, "model": model},
                             config={"recursion_limit": 30})
    except Exception as e:  # noqa: BLE001
        return {"sql": None, "explanation": None, "columns": [], "rows": [], "rowCount": 0,
                "fallback": True, "fallbackHint": f"Python Agent 异常: {e}", "engine": "PYTHON",
                "steps": [{"name": "异常", "detail": str(e)[:200], "ok": False}],
                "tookMs": int((time.time() - started) * 1000)}
    return {
        "sql": final.get("sql"),
        "explanation": final.get("explanation"),
        "columns": final.get("columns") or [],
        "rows": final.get("rows") or [],
        "rowCount": final.get("row_count") or 0,
        "fallback": bool(final.get("fallback")),
        "fallbackHint": final.get("fallback_hint"),
        "engine": "PYTHON",
        "steps": final.get("steps") or [],
        "tookMs": int((time.time() - started) * 1000),
    }
