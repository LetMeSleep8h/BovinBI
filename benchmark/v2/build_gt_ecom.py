#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BovinBI benchmark v2 · 电商真值构建(独立聚合 ecom CSV,与被测系统零共享代码)

用法: python3 build_gt_ecom.py   → gt-ecom.json
锚定数据末日 2018-08-29(可 BENCH_ANCHOR 覆盖);相对口径以锚点日为"今天"。
"""
import csv
import json
import os
import sys
from collections import defaultdict
from datetime import date, timedelta

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
from cases_ecom import CASES, ANCHOR

DWH = os.path.join(HERE, "..", "..", "src", "main", "resources", "dataset", "real", "ecom")


def load():
    products = {r["product_id"]: r for r in csv.DictReader(open(f"{DWH}/ecom_dim_product.csv", encoding="utf-8"))}
    customers = {r["customer_id"]: r for r in csv.DictReader(open(f"{DWH}/ecom_dim_customer.csv", encoding="utf-8"))}
    facts = []
    for r in csv.DictReader(open(f"{DWH}/ecom_fact_order_item.csv", encoding="utf-8")):
        facts.append(dict(
            order=r["order_id"], date=r["order_date"],
            product=r["product_id"], customer=r["customer_id"],
            price=float(r["price"]), freight=float(r["freight_value"]),
            category=products[r["product_id"]]["category"],
            state=customers[r["customer_id"]]["state"],
        ))
    return products, customers, facts


PRODUCTS, CUSTOMERS, FACTS = load()


def _mk(y, m):
    return f"{y:04d}-{m:02d}-01"


def resolve_period(p):
    kind = p[0]
    if kind == "all":
        return ("0000-01-01", "9999-12-31")
    if kind == "ym":
        y, m = p[1], p[2]
        ny, nm = (y + 1, 1) if m == 12 else (y, m + 1)
        return (_mk(y, m), _mk(ny, nm))
    if kind == "range":
        return (p[1], p[2])
    if kind == "last_month":
        y, m = (ANCHOR.year, ANCHOR.month - 1) if ANCHOR.month > 1 else (ANCHOR.year - 1, 12)
        ny, nm = (y + 1, 1) if m == 12 else (y, m + 1)
        return (_mk(y, m), _mk(ny, nm))
    if kind == "last3m":
        start = (ANCHOR.replace(day=1) - timedelta(days=1)).replace(day=1)
        # 近3个完整月:锚点月往前推2个整月的月初
        y, m = ANCHOR.year, ANCHOR.month
        for _ in range(3):
            m -= 1
            if m == 0:
                m = 12; y -= 1
        return (_mk(y, m), _mk(ANCHOR.year, ANCHOR.month))
    if kind == "last12m":
        y, m = ANCHOR.year, ANCHOR.month
        for _ in range(12):
            m -= 1
            if m == 0:
                m = 12; y -= 1
        return (_mk(y, m), _mk(ANCHOR.year, ANCHOR.month))
    raise ValueError(kind)


def rows_in(period, filters=None):
    fl = filters or {}
    lo, hi = resolve_period(period)

    def keep(r):
        return (lo <= r["date"] < hi
                and (not fl.get("state") or r["state"] == fl["state"])
                and (not fl.get("category") or r["category"] == fl["category"]))
    return [r for r in FACTS if keep(r)]


AGG = {
    "sum_price": lambda rows: sum(r["price"] for r in rows),
    "sum_freight": lambda rows: sum(r["freight"] for r in rows),
    "avg_price": lambda rows: (sum(r["price"] for r in rows) / len(rows)) if rows else 0.0,
    "avg_freight": lambda rows: (sum(r["freight"] for r in rows) / len(rows)) if rows else 0.0,
    "count_orders": lambda rows: len({r["order"] for r in rows}),
    "aov": lambda rows: (sum(r["price"] for r in rows) / len({r["order"] for r in rows})) if rows else 0.0,
}


def group_by(rows, dim):
    g = defaultdict(list)
    for r in rows:
        g[r[dim]].append(r)
    return g


CUSTOM = {}


def reg(name):
    def deco(fn):
        CUSTOM[name] = fn
        return fn
    return deco


@reg("gap_top2_category")
def _():
    g = group_by(rows_in(("all",)), "category")
    ranked = sorted((sum(r["price"] for r in v) for v in g.values()), reverse=True)
    gap = round(ranked[0] - ranked[1], 2) if len(ranked) >= 2 else 0.0
    return dict(kind="any_num", expect=[gap],
                note=f"第一比第二多 {gap:.2f}(系统只要给出这个差值或两个原值之一可推算即算对)")


@reg("may_vs_april_2018")
def _():
    may = AGG["sum_price"](rows_in(("ym", 2018, 5)))
    apr = AGG["sum_price"](rows_in(("ym", 2018, 4)))
    return dict(kind="any_num", expect=[round(may, 2), round(apr, 2)],
                note=f"5月={may:.2f} 4月={apr:.2f};出现任一数值即算如实作答")


def build():
    out = []
    for c in CASES:
        spec = c["spec"]
        entry = dict(id=c["id"], cat=c["cat"], q=c["q"])
        if spec["kind"] == "custom":
            entry.update(CUSTOM[spec["fn"]]())
        elif spec["kind"] == "no_data":
            entry.update(kind="no_data")
        elif spec["kind"] == "scalar":
            v = AGG[spec["agg"]](rows_in(spec["period"], spec.get("filters")))
            entry.update(kind="scalar", expect=round(v, 4))
        elif spec["kind"] == "group":
            g = group_by(rows_in(spec["period"], spec.get("filters")), spec["dim"])
            entry.update(kind="group", dim=spec["dim"],
                        expect={k: round(AGG[spec["agg"]](v), 4) for k, v in g.items()})
        elif spec["kind"] == "top":
            g = group_by(rows_in(spec["period"], spec.get("filters")), spec["dim"])
            ranked = sorted(((k, round(AGG[spec["agg"]](v), 4)) for k, v in g.items()),
                            key=lambda kv: (kv[1], kv[0]), reverse=(spec["dir"] != "asc"))
            entry.update(kind="top", expect=[list(kv) for kv in ranked[:spec["n"]]])
        elif spec["kind"] == "trend":
            rows = rows_in(spec["period"], spec.get("filters"))
            g = defaultdict(list)
            for r in rows:
                g[r["date"][:7]].append(r)
            expect = {k: round(AGG[spec["agg"]](v), 4) for k, v in g.items()}
            entry.update(kind="trend", bucket="month", expect=expect)
        out.append(entry)

    gt = dict(anchor=ANCHOR.isoformat(),
              dataset="电商零售·巴西Olist",
              generated_at=date.today().isoformat(),
              data_window=[min(r["date"] for r in FACTS), max(r["date"] for r in FACTS)],
              n_facts=len(FACTS), cases=out)
    dest = os.path.join(HERE, "gt-ecom.json")
    json.dump(gt, open(dest, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"电商真值:{len(out)} 条 → {dest}")
    print(f"锚定 {ANCHOR} | 窗口 {gt['data_window']} | {len(FACTS)} 行明细")


if __name__ == "__main__":
    build()
