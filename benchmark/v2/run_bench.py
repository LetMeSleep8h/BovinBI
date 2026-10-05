#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BovinBI benchmark v2 · 黑盒答案级评测器

对运行中的后端(任意引擎,离线/LLM/agent 均可)逐题发问,取 /api/chat/ask 返回的
结构化结果(rows 数值)与 gt.json 的独立真值比对——不看 SQL 长什么样,只看答案对不对。

判定四档:
  PASS        答案数值与独立真值一致(容差内)
  HONEST      坦率未答:返回空结果或显式降级(fallback)——不丢人,但不算会
  WRONG       给出了数值,但与真值不符(答错)
  FABRICATE   负样本(no_data)却给出了数值 —— 编造,一票否决级问题

用法: 先启动后端,再 python3 run_bench.py [--base http://localhost:8080]
"""
import argparse
import json
import os
import statistics
import time
import urllib.request
from datetime import date

HERE = os.path.dirname(os.path.abspath(__file__))
PASS, HONEST, WRONG, FABRICATE, ERROR = "PASS", "HONEST", "WRONG", "FABRICATE", "ERROR"

ABS_TOL, REL_TOL = 0.06, 5e-4  # 吸收引擎 ROUND(...,2) 的舍入;语义性偏差(错月份/错口径)必然超差


def call(base, path, payload=None, token=None):
    req = urllib.request.Request(base + path, method="POST" if payload is not None else "GET")
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    data = json.dumps(payload).encode() if payload is not None else None
    with urllib.request.urlopen(req, data=data, timeout=90) as resp:
        return json.loads(resp.read())


def to_num(v):
    """'1234.56'→1234.56;'2026-03'/'荷斯坦'→None"""
    if isinstance(v, bool):
        return None
    if isinstance(v, (int, float)):
        return float(v)
    try:
        s = str(v).replace(",", "").strip()
        if not s:
            return None
        return float(s)
    except ValueError:
        return None


def num_close(a, b):
    return abs(a - b) <= max(ABS_TOL, REL_TOL * max(abs(a), abs(b)))


def norm_key(s, kind):
    """时间/序数桶标签归一化:兼容 '2026-03'/'26-03'/'202603'/'Q1'/'第1季度' 等写法"""
    digits = "".join(ch for ch in str(s) if ch.isdigit())
    if kind == "month":
        if len(digits) == 4:
            return "20" + digits
        if len(digits) == 6:
            return digits
    if kind == "day" and len(digits) == 8:
        return digits
    if kind == "quarter" and digits:
        return str(int(digits[-1]))  # 'Q1'/'2026-Q1'/'第1季度'/'1' → 1..4(取末位数字)
    return str(s).strip()


def norm_label(s):
    return str(s).strip()


def label_match(got, want):
    g, w = norm_label(got), norm_label(want)
    return g == w or (len(w) > 1 and w in g) or (len(g) > 1 and g in w)


class Answer:
    """一次提问返回的结构化拆解"""

    def __init__(self, payload):
        self.rows = (payload or {}).get("rows") or []
        self.fallback = bool((payload or {}).get("fallback"))
        self.engine = (payload or {}).get("engine") or "?"
        self.sql = (payload or {}).get("sql") or ""
        self.cells = [c for row in self.rows for c in row.values()]
        self.nums = [n for n in (to_num(c) for c in self.cells) if n is not None]

    @property
    def empty(self):
        return len(self.rows) == 0

    def all_zero(self):
        return not self.nums or all(abs(n) < ABS_TOL for n in self.nums)

    def sample(self, n=2):
        return " ; ".join(str(r) for r in self.rows[:n])[:300]


def judge(case, ans):
    """返回 (verdict, detail)"""
    kind, expect = case["kind"], case.get("expect")

    if kind == "no_data":
        if ans.fallback or ans.empty:
            return PASS, "未编造(空/降级)"
        return FABRICATE, f"负样本却返回了数值行:{ans.sample()}"

    if kind == "empty_ok":
        if ans.fallback or ans.all_zero():
            return PASS, "空/0,符合真值"
        return WRONG, f"真值为空/0,却返回非零数值:{ans.sample()}"

    if ans.fallback or ans.empty:
        return HONEST, "未回答(空结果或降级)"

    if kind == "scalar":
        if any(num_close(n, expect) for n in ans.nums):
            return PASS, f"命中真值 {expect}"
        return WRONG, f"期望 {expect},返回:{ans.sample()}"

    if kind == "any_num":
        for alt in expect:
            if any(num_close(n, alt) for n in ans.nums):
                return PASS, f"命中真值(口径之一){alt}"
        return WRONG, f"期望 {expect} 之一,返回:{ans.sample()}"

    if kind == "nums":
        missing = [e for e in expect if not any(num_close(n, e) for n in ans.nums)]
        if not missing:
            return PASS, f"全部 {len(expect)} 个真值数值均出现"
        return WRONG, f"缺少真值数值 {missing},返回:{ans.sample()}"

    if kind == "label":
        if any(isinstance(c, str) and label_match(c, expect) for c in ans.cells):
            return PASS, f"命中标签 {expect}"
        return WRONG, f"期望标签 {expect},返回:{ans.sample()}"

    if kind == "labels":
        missing = [w for w in expect if not any(isinstance(c, str) and label_match(c, w) for c in ans.cells)]
        if not missing:
            return PASS, f"全部 {len(expect)} 个标签均出现"
        return WRONG, f"缺少标签 {missing},返回:{ans.sample()}"

    if kind in ("group", "trend"):
        if kind == "group" and case.get("dim") == "quarter":
            key_norm_kind = "quarter"
        elif kind == "trend":
            key_norm_kind = case.get("bucket")  # month / day
        else:
            key_norm_kind = None
        # 行内取前两列:第一列维度标签,第二列数值(多列时扫描数值列)
        got = {}
        for row in ans.rows:
            vals = list(row.values())
            lab = vals[0] if vals else ""
            v = next((to_num(x) for x in vals[1:] if to_num(x) is not None), None)
            if v is not None:
                got[norm_key(lab, key_norm_kind) if key_norm_kind else norm_label(lab)] = v
        if not got:
            return HONEST, "返回行中无可用数值"
        exp = {norm_key(k, key_norm_kind) if key_norm_kind else norm_label(k): v
               for k, v in expect.items()}
        missing = {k: v for k, v in exp.items() if k not in got or not num_close(got[k], v)}
        extra = [k for k in got if k not in exp]
        if not missing:
            detail = f"{len(exp)} 个桶全部命中"
            if extra:
                detail += f"(另有疑似超范围桶:{extra[:6]})"
            return PASS, detail
        wrong = {k: got[k] for k, v in missing.items() if k in got}
        absent = [k for k in missing if k not in got]
        return WRONG, (f"桶值不符 {wrong} / 缺桶 {absent} / 多桶 {extra[:6]} 返回:{ans.sample()}")

    if kind == "top":
        got_labels = [list(r.values())[0] for r in ans.rows if r]
        ok = True
        detail = []
        for i, (lab, val) in enumerate(expect):
            if i >= len(got_labels):
                ok = False
                detail.append(f"缺第{i + 1}名 {lab}")
                continue
            if not label_match(got_labels[i], lab):
                ok = False
                detail.append(f"第{i + 1}名期望 {lab} 实得 {got_labels[i]}")
        if ok:
            return PASS, f"前 {len(expect)} 名次序与真值一致"
        return WRONG, ";".join(detail) + f" 返回:{ans.sample()}"

    return ERROR, f"未知 kind:{kind}"


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base", default="http://localhost:8080")
    ap.add_argument("--user", default="admin")
    ap.add_argument("--password", default="bovin123")
    ap.add_argument("--gt", default=os.path.join(HERE, "gt.json"))
    args = ap.parse_args()

    gt = json.load(open(args.gt, encoding="utf-8"))
    if gt["anchor"] != date.today().isoformat():
        print(f"⚠️  gt.json 锚定于 {gt['anchor']},今天是 {date.today()}:相对时间案例真值可能过期,建议重跑 build_gt.py")

    r = call(args.base, "/api/auth/login", {"username": args.user, "password": args.password})
    token = r["data"]["token"]
    ds = call(args.base, "/api/datasets", token=token)["data"][0]
    sid = call(args.base, "/api/chat/sessions", {"datasetId": ds["id"]}, token=token)["data"]["sessionId"]

    results = []
    for c in gt["cases"]:
        t0 = time.perf_counter()
        try:
            resp = call(args.base, "/api/chat/ask", {"sessionId": sid, "question": c["q"]}, token)
            ans = Answer((resp.get("data") or {}).get("payload"))
        except Exception as e:
            results.append(dict(c, verdict=ERROR, detail=f"HTTP 异常:{e}", took=0, engine="?", sql=""))
            continue
        took = (time.perf_counter() - t0) * 1000
        verdict, detail = judge(c, ans)
        results.append(dict(c, verdict=verdict, detail=detail, took=round(took), engine=ans.engine, sql=ans.sql))

    # ---------- 汇总 ----------
    n = len(results)
    cnt = {v: sum(1 for r in results if r["verdict"] == v) for v in (PASS, HONEST, WRONG, FABRICATE, ERROR)}
    cats = {}
    for r in results:
        cats.setdefault(r["cat"], dict(total=0, **{v: 0 for v in (PASS, HONEST, WRONG, FABRICATE, ERROR)}))
        cats[r["cat"]]["total"] += 1
        cats[r["cat"]][r["verdict"]] += 1
    engines = {}
    for r in results:
        engines[r["engine"]] = engines.get(r["engine"], 0) + 1

    lines = []
    lines.append("# BovinBI benchmark v2 · 答案级黑盒评测报告")
    lines.append("")
    lines.append(f"- 时间:{time.strftime('%Y-%m-%d %H:%M')} 目标:{args.base} 数据窗口:{gt['data_window']}(真值锚定 {gt['anchor']})")
    lines.append(f"- 真值:纯 Python 独立聚合 {gt['n_facts']} 条原始 CSV(与被测系统零共享代码);比对对象=API 返回的数值答案,不看 SQL")
    lines.append("")
    lines.append(f"## 总览:{n} 题")
    lines.append("")
    lines.append(f"| 指标 | 数值 |")
    lines.append(f"| --- | --- |")
    lines.append(f"| **答对率(严格)** | **{cnt[PASS]}/{n} = {cnt[PASS] * 100.0 / n:.1f}%** |")
    lines.append(f"| 坦率未答(空/降级,不算会) | {cnt[HONEST]} |")
    lines.append(f"| 答错(给了数但不对) | {cnt[WRONG]} |")
    lines.append(f"| **编造(负样本给出数据)** | **{cnt[FABRICATE]}** |")
    if cnt[ERROR]:
        lines.append(f"| HTTP/解析异常 | {cnt[ERROR]} |")
    lat = [r["took"] for r in results if r["verdict"] != ERROR]
    lines.append(f"| 延迟 | P50={statistics.median(lat):.0f}ms 均值={statistics.mean(lat):.0f}ms |")
    lines.append(f"| 引擎分布 | {engines} |")
    lines.append("")
    lines.append("## 分类明细")
    lines.append("")
    lines.append("| 分类 | 题数 | 答对 | 坦率未答 | 答错 | 编造 | 答对率 |")
    lines.append("| --- | --- | --- | --- | --- | --- | --- |")
    for cat, c in cats.items():
        lines.append(f"| {cat} | {c['total']} | {c[PASS]} | {c[HONEST]} | {c[WRONG]} | {c[FABRICATE]} | {c[PASS] * 100.0 / c['total']:.0f}% |")
    lines.append("")
    lines.append("## 未答对题目明细")
    lines.append("")
    for r in results:
        if r["verdict"] == PASS:
            continue
        exp = r.get("expect")
        exp_s = exp if not isinstance(exp, (dict, list)) else json.dumps(exp, ensure_ascii=False)[:220]
        lines.append(f"- **[{r['verdict']}] {r['id']} ({r['cat']})** {r['q']}")
        lines.append(f"  - 真值:{exp_s} {r.get('note', '')}")
        lines.append(f"  - 判定:{r['detail']}")
        lines.append(f"  - 引擎 {r['engine']} · {r['took']}ms · SQL:`{(r['sql'] or '')[:160]}`")
    lines.append("")

    stamp = time.strftime("%Y%m%d-%H%M")
    report_path = os.path.join(HERE, f"report-{stamp}.md")
    open(report_path, "w", encoding="utf-8").write("\n".join(lines))
    open(os.path.join(HERE, "results.json"), "w", encoding="utf-8").write(
        json.dumps(results, ensure_ascii=False, indent=1))

    print("\n".join(lines[:40]))
    print(f"\n报告:{report_path}\n明细:{os.path.join(HERE, 'results.json')}")


if __name__ == "__main__":
    main()
