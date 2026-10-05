"""Python Agent 本体:两种模式,同一个契约。

- LLM 模式(配置了 LLM_API_KEY):调 OpenAI 兼容接口,基于 MCP 取回的 Schema
  生成 SQL,再经 MCP executeSql 执行 —— 工具循环的最小闭环;
- 离线模式(无 Key):内置规则(agent 版),按 Schema 字段元数据拼单表 SQL,
  同样经 MCP 执行 —— 保证零外部依赖也能演示跨语言链路。

安全设计:Python 只做"智力"(选表/拼 SQL),能不能跑永远由 Java 底座的
SqlGuard 决定(表白名单/仅 SELECT/强制 LIMIT),与 Java 引擎同一套边界。
"""

import json
import os
import re
import time

from . import mcp_client

LLM_BASE_URL = os.environ.get("LLM_BASE_URL", "https://api.deepseek.com")
LLM_API_KEY = os.environ.get("LLM_API_KEY", "")
LLM_MODEL = os.environ.get("LLM_MODEL", "deepseek-chat")

SYSTEM_PROMPT = """#Role: 你是数仓 NL2SQL 生成 Agent。只负责生成 SQL,执行由底座负责。
#Rules:
1. 只输出 JSON:{"sql": "...", "explanation": "一句话中文解释"}。
2. 只允许一条 SELECT;表和列必须来自 #Schema,禁止编造。
3. 结果末尾必须有 LIMIT;输出列用中文别名。"""


def answer(dataset_id: int, question: str) -> dict:
    """一次问答:返回与 Java AnswerPayload 同构的字典(子集)+ steps 轨迹"""
    steps = []

    def step(name, detail, ok=True):
        steps.append({"name": name, "detail": str(detail)[:200], "ok": ok})

    started = time.time()
    try:
        step("getSchema(MCP)", "从 Java 底座召回表结构与字段口径")
        schema_text = mcp_client.get_schema(dataset_id, question)
        fields = _parse_fields(schema_text)

        sql, explanation = _generate(dataset_id, question, schema_text, fields, step)

        step("executeSql(MCP)", sql[:120])
        preview = mcp_client.execute_sql(dataset_id, sql)

        columns, rows = _parse_preview(preview)
        return {
            "sql": sql,
            "explanation": explanation,
            "columns": columns,
            "rows": rows,
            "rowCount": len(rows),
            "fallback": False,
            "fallbackHint": None,
            "engine": "PYTHON",
            "steps": steps,
            "tookMs": int((time.time() - started) * 1000),
        }
    except mcp_client.McpError as e:
        # 工具层失败(守护拒绝/执行报错):错误即数据,交给上层降级链
        step("MCP失败", str(e)[:200], False)
        return _fallback(f"Python Agent 执行失败: {e}", steps, started)
    except Exception as e:  # noqa: BLE001
        step("异常", str(e)[:200], False)
        return _fallback(f"Python Agent 异常: {e}", steps, started)


def _generate(dataset_id, question, schema_text, fields, step):
    if LLM_API_KEY:
        step("LLM生成", f"{LLM_MODEL} 按 Schema 生成 SQL")
        return _llm_generate(question, schema_text)
    step("规则生成", "离线模式:按字段元数据拼装 SQL(配置 LLM_API_KEY 可升级为 LLM 生成)")
    return _rule_generate(question, fields)


# ---------------- LLM 模式 ----------------

def _llm_generate(question, schema_text) -> tuple[str, str]:
    import httpx

    resp = httpx.post(
        f"{LLM_BASE_URL.rstrip('/')}/chat/completions",
        headers={"Authorization": f"Bearer {LLM_API_KEY}"},
        json={
            "model": LLM_MODEL,
            "temperature": 0.0,
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": f"#Schema:\n{schema_text}\n\n#Question: {question}"},
            ],
        },
        timeout=40,
    )
    resp.raise_for_status()
    raw = resp.json()["choices"][0]["message"]["content"]
    m = re.search(r"\{.*}", raw, re.DOTALL)
    if not m:
        raise RuntimeError("LLM 未返回 JSON")
    data = json.loads(m.group(0))
    sql = (data.get("sql") or "").strip()
    if not sql:
        raise RuntimeError("LLM 未返回 SQL")
    return sql, data.get("explanation", "")


# ---------------- 离线规则模式(单表白名单) ----------------

_TIME_PATTERNS = [
    (re.compile(r"近(\d{1,3})天|最近(\d{1,3})天"), "days"),
    (re.compile(r"近(\d{1,2})个月|最近(\d{1,2})个月"), "months"),
]


def _rule_generate(question, fields):
    q = question.lower()
    metric = _match_field(fields, "METRIC", q) or _first(fields, "METRIC")
    dim = _match_field(fields, "DIMENSION", q)
    date_field = next((f for f in fields if f["field_type"] == "DIMENSION"
                       and f["data_type"] == "DATE"), None)

    if metric is None:
        raise RuntimeError("Schema 中未找到指标字段,离线规则无法生成")

    table = metric["table"]
    # 离线规则只做单表:维度不在指标表则丢弃分组,保证 SQL 可执行
    if dim and dim["table"] != table:
        dim = None
    expr = _agg_expr(metric)

    topn = None
    m = re.search(r"(?:top\s*(\d{1,2}))|(?:前\s*(\d{1,2}))", q)
    if m:
        topn = int(m.group(1) or m.group(2))
    trend = any(k in q for k in ("趋势", "每月", "按月", "走势"))
    ratio = any(k in q for k in ("占比", "份额", "构成", "分布"))

    selects, group = [], None
    if trend and date_field and date_field["table"] == table:
        col = f"DATE_FORMAT({date_field['column']}, '%Y-%m')"
        selects.append((col, "月份"))
        group = "月份"
    elif dim:
        selects.append((dim["column"], dim["alias"]))
        group = dim["alias"]
    selects.append((expr, metric["alias"]))

    where = _time_where(question, date_field, q)
    sql = f"SELECT {', '.join(f'{c} AS {a}' for c, a in selects)} FROM {table}"
    if where:
        sql += f" WHERE {where}"
    if group:
        sql += f" GROUP BY {group}"
    order = f"{metric['alias']} DESC" if (topn or ratio) else (group or metric["alias"])
    sql += f" ORDER BY {order} LIMIT {topn if topn else 1000}"

    label = ("Top%d %s" % (topn, dim["alias"])) if topn and dim else (
        f"按{group}{metric['alias']}" if group else f"{metric['alias']}")
    return sql, f"[Python 离线规则] {label}"


def _time_where(question, date_field, q):
    if date_field is None:
        return None
    col = date_field["column"]
    for pat, kind in _TIME_PATTERNS:
        m = pat.search(question)
        if m:
            n = int(m.group(1) or m.group(2))
            if kind == "days":
                return f"{col} >= DATE_SUB(CURDATE(), INTERVAL {n - 1} DAY) AND {col} < DATE_ADD(CURDATE(), INTERVAL 1 DAY)"
            return f"{col} >= DATE_SUB(DATE_FORMAT(CURDATE(), '%Y-%m-01'), INTERVAL {n - 1} MONTH) AND {col} < DATE_ADD(CURDATE(), INTERVAL 1 DAY)"
    if "今年" in q:
        return f"{col} >= DATE_FORMAT(CURDATE(), '%Y-01-01') AND {col} < DATE_FORMAT(DATE_ADD(CURDATE(), INTERVAL 1 YEAR), '%Y-01-01')"
    if "去年" in q:
        return f"{col} >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 1 YEAR), '%Y-01-01') AND {col} < DATE_FORMAT(CURDATE(), '%Y-01-01')"
    if "上个月" in q or "上月" in q:
        return f"{col} >= DATE_FORMAT(DATE_SUB(CURDATE(), INTERVAL 1 MONTH), '%Y-%m-01') AND {col} < DATE_FORMAT(CURDATE(), '%Y-%m-01')"
    return None


def _match_field(fields, ftype, q):
    """词面匹配字段:别名/同义词出现在问题里(长词优先)"""
    cands = [f for f in fields if f["field_type"] == ftype]
    for f in sorted(cands, key=lambda x: -len(x["alias"])):
        if f["alias"] and f["alias"] in q:
            return f
        for syn in f["synonyms"]:
            if syn and syn in q:
                return f
    return None


def _first(fields, ftype):
    return next((f for f in fields if f["field_type"] == ftype), None)


def _agg_expr(metric):
    agg = metric["agg_type"]
    col = metric["column"]
    if agg == "SUM":
        return f"ROUND(SUM({col}), 2)"
    if agg == "AVG":
        return f"ROUND(AVG({col}), 2)"
    if agg == "COUNT_DISTINCT":
        return f"COUNT(DISTINCT {col})"
    return col


# ---------------- Schema/预览解析 ----------------

_FIELD_LINE = re.compile(r"^(\w+)\.(\w+)\s+\S+\s+业务名:(\S+?)(?:[((].*)?$")


def _parse_fields(schema_text):
    """解析 Java getSchema 的紧凑文本行:`table.col TYPE 业务名:alias(指标,聚合:SUM)`"""
    fields = []
    for line in schema_text.splitlines():
        line = line.strip()
        if not line or line.startswith("【"):
            continue
        m = _FIELD_LINE.match(line)
        if not m:
            continue
        table, column, alias = m.group(1), m.group(2), m.group(3)
        ftype = "METRIC" if ("指标" in line) else "DIMENSION"
        agg = "SUM"
        am = re.search(r"聚合方式:(\w+)", line)
        if am:
            agg = am.group(1)
        dtype = "DECIMAL"
        dm = re.match(r"^(\w+)\.(\w+)\s+(\S+)", line)
        if dm:
            dtype = dm.group(3)
        synonyms = [s for s in re.findall(r"同义词:\[(.*?)\]", line) for s in s.split(",")]
        fields.append({
            "table": table, "column": column, "alias": alias.rstrip("(维度)指标"),
            "field_type": ftype, "agg_type": agg, "data_type": dtype,
            "synonyms": [s.strip() for s in synonyms if s.strip()],
        })
    return fields


def _parse_preview(preview):
    """解析 executeSql 预览文本 → (columns, rows);只取预览行(上限由守护 LIMIT 兜底)"""
    lines = [l for l in preview.splitlines() if l.strip()]
    columns, rows = [], []
    for line in lines:
        if line.startswith("列:"):
            names = [c.strip() for c in line[2:].split("|")]
            columns = [{"name": n, "type": "VARCHAR"} for n in names if n]
        elif not columns or any(k in line for k in ("守护拒绝", "执行失败")):
            continue
        else:
            vals = [v.strip() for v in line.split("|")]
            if len(vals) == len(columns):
                rows.append({c["name"]: _num(v) for c, v in zip(columns, vals)})
    return columns, rows


def _num(v):
    try:
        return int(v)
    except ValueError:
        try:
            return float(v)
        except ValueError:
            return v


def _fallback(hint, steps, started):
    return {
        "sql": None, "explanation": None, "columns": [], "rows": [], "rowCount": 0,
        "fallback": True, "fallbackHint": hint, "engine": "PYTHON",
        "steps": steps, "tookMs": int((time.time() - started) * 1000),
    }
