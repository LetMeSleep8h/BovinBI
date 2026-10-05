#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BovinBI benchmark v2 · 优化对比器

对比两次评测的 results.json(逐题 verdict 相同 id 对齐),输出:
  总量指标增减(答对率/HONEST/WRONG/FABRICATE)、分类增减、逐题翻转明细(修好/退化/口径变化)。

用法:
  python3 compare.py results-baseline.json results.json           # 基线 vs 最新
  python3 compare.py results-baseline.json report-xxx/results.json  # 任意两轮
"""
import argparse
import json
import os
import sys

V = ("PASS", "HONEST", "WRONG", "FABRICATE", "ERROR")


def load(path):
    rs = json.load(open(path, encoding="utf-8"))
    return {r["id"]: r for r in rs}


def stats(runs):
    s = {v: 0 for v in V}
    for r in runs.values():
        s[r["verdict"]] = s.get(r["verdict"], 0) + 1
    n = len(runs)
    s["_n"] = n
    s["_acc"] = s["PASS"] * 100.0 / n if n else 0.0
    return s


def cat_stats(runs):
    cats = {}
    for r in runs.values():
        c = cats.setdefault(r["cat"], {v: 0 for v in V})
        c[r["verdict"]] = c.get(r["verdict"], 0) + 1
    return cats


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("baseline")
    ap.add_argument("current")
    ap.add_argument("--out", default=None, help="对比报告落盘路径(默认仅打印)")
    args = ap.parse_args()

    base, cur = load(args.baseline), load(args.current)
    ids = [i for i in base if i in cur]
    if len(ids) < len(base):
        gone = set(base) - set(cur)
        print(f"⚠️  基线有 {len(gone)} 题在新结果中缺失(案例集变更?):{sorted(gone)}", file=sys.stderr)
    new_ids = set(cur) - set(base)
    if new_ids:
        print(f"ℹ️  新结果多出 {len(new_ids)} 题(新增案例,单独统计):{sorted(new_ids)}", file=sys.stderr)

    sb, sc = stats({i: base[i] for i in ids}), stats({i: cur[i] for i in ids})
    cb, cc = cat_stats({i: base[i] for i in ids}), cat_stats({i: cur[i] for i in ids})

    def delta(b, c, suffix=""):
        d = c - b
        return f"{c}{suffix}({d:+}{suffix})"

    L = []
    L.append("# benchmark v2 · 优化对比")
    L.append("")
    L.append(f"- 基线:{os.path.basename(args.baseline)} vs 本轮:{os.path.basename(args.current)}(对齐 {len(ids)} 题)")
    L.append("")
    L.append("## 总量")
    L.append("")
    L.append("| 指标 | 基线 | 本轮 | 变化 |")
    L.append("| --- | --- | --- | --- |")
    L.append(f"| **答对率** | {sb['_acc']:.1f}% | {sc['_acc']:.1f}% | {delta(round(sb['_acc'], 1), round(sc['_acc'], 1), '%')} |")
    for v in ("HONEST", "WRONG", "FABRICATE"):
        L.append(f"| {v} | {sb[v]} | {sc[v]} | {delta(sb[v], sc[v])} |")
    L.append("")
    L.append("## 分类")
    L.append("")
    L.append("| 分类 | 基线答对率 | 本轮答对率 | 变化 |")
    L.append("| --- | --- | --- | --- |")
    for cat in sorted(set(cb) | set(cc)):
        b, c = cb.get(cat, {v: 0 for v in V}), cc.get(cat, {v: 0 for v in V})
        nb = sum(b[v] for v in V)
        nc = sum(c[v] for v in V)
        if not nb or not nc:
            continue
        L.append(f"| {cat} | {b['PASS']}/{nb} = {b['PASS'] * 100.0 / nb:.0f}% "
                 f"| {c['PASS']}/{nc} = {c['PASS'] * 100.0 / nc:.0f}% "
                 f"| {delta(b['PASS'], c['PASS'])} |")
    L.append("")
    fixed = [i for i in ids if base[i]["verdict"] != "PASS" and cur[i]["verdict"] == "PASS"]
    broken = [i for i in ids if base[i]["verdict"] == "PASS" and cur[i]["verdict"] != "PASS"]
    shifted = [i for i in ids if base[i]["verdict"] != cur[i]["verdict"]
               and i not in fixed and i not in broken]
    L.append(f"## 逐题翻转:修好 {len(fixed)} · 退化 {len(broken)} · 其他变化 {len(shifted)}")
    L.append("")
    for tag, group in (("✅ 修好", fixed), ("❌ 退化", broken), ("↪️ 变化", shifted)):
        for i in sorted(group):
            b, c = base[i], cur[i]
            L.append(f"- {tag} **{i}** ({c['cat']}) {c['q']}")
            L.append(f"  - {b['verdict']} → {c['verdict']}:{c['detail'][:160]}")
    L.append("")

    text = "\n".join(L)
    print(text)
    if args.out:
        open(args.out, "w", encoding="utf-8").write(text)
        print(f"\n对比报告:{args.out}")


if __name__ == "__main__":
    main()
