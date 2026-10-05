#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""BovinBI benchmark v2 · 独立真值构建器(反拟合的核心)

从 src/main/resources/dataset/dwh/*.csv(与后端 DataLoader 同源装载的原始数据)出发,
用【纯 Python 聚合】计算每道案例的期望答案——全程不写 SQL、不复用被测系统任何代码,
真值与被测系统的唯一共享物是数据本身。

用法: python3 build_gt.py   →  gt.json(含锚定日期;相对时间案例换日重跑需重新构建)
"""
import csv
import json
import os
import sys
from collections import defaultdict
from datetime import date, datetime

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from cases import CASES, NAMED_PERIODS

DWH = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                   "..", "..", "src", "main", "resources", "dataset", "dwh")


def load():
    farms = {int(r["id"]): r for r in csv.DictReader(open(f"{DWH}/dim_farm.csv", encoding="utf-8"))}
    cattle = {int(r["id"]): r for r in csv.DictReader(open(f"{DWH}/dim_cattle.csv", encoding="utf-8"))}
    facts = []
    for r in csv.DictReader(open(f"{DWH}/fact_milk.csv", encoding="utf-8")):
        facts.append(dict(
            date=r["record_date"],
            cattle_id=int(r["cattle_id"]),
            farm_id=int(r["farm_id"]),
            milk=float(r["milk_yield"]),
            fat=float(r["fat_rate"]),
            protein=float(r["protein_rate"]),
            stage=r["lactation_stage"],
        ))
    return farms, cattle, facts


FARMS, CATTLE, FACTS = load()
ANCHOR = date.today()


def resolve_period(p):
    if p[0] in NAMED_PERIODS:
        return NAMED_PERIODS[p[0]](ANCHOR)
    return (p[0], p[1])


def fact_filter(filters):
    """把维度过滤条件解析为 fact 行谓词(维度值经 dim 表映射,与被测系统同义但实现独立)"""
    fl = filters or {}
    farm_ids = {fid for fid, f in FARMS.items()
                if (not fl.get("region") or f["region"] == fl["region"])
                and (not fl.get("scale") or f["scale"] == fl["scale"])
                and (not fl.get("farm") or fl["farm"] in f["farm_name"])}
    cattle_ids = {cid for cid, c in CATTLE.items()
                  if (not fl.get("breed") or c["breed"] == fl["breed"])
                  and (not fl.get("barn") or c["barn"] == fl["barn"])
                  and (not fl.get("parity_min") or int(c["parity"]) >= fl["parity_min"])}

    def keep(row):
        return (row["farm_id"] in farm_ids and row["cattle_id"] in cattle_ids
                and (not fl.get("stage") or row["stage"] == fl["stage"]))
    return keep


AGG = {
    "sum_milk": lambda rows: sum(r["milk"] for r in rows),
    "avg_milk": lambda rows: sum(r["milk"] for r in rows) / len(rows),
    "avg_fat": lambda rows: sum(r["fat"] for r in rows) / len(rows),
    "avg_protein": lambda rows: sum(r["protein"] for r in rows) / len(rows),
    "count_cattle": lambda rows: len({r["cattle_id"] for r in rows}),
}


def rows_in(period, filters=None):
    keep = fact_filter(filters)
    return [r for r in FACTS if period[0] <= r["date"] < period[1] and keep(r)]


def dim_value(row, dim):
    if dim == "farm":
        return FARMS[row["farm_id"]]["farm_name"]
    if dim == "region":
        return FARMS[row["farm_id"]]["region"]
    if dim in ("breed", "barn"):
        return CATTLE[row["cattle_id"]][dim]
    if dim == "stage":
        return row["stage"]
    if dim == "quarter":
        return str((int(row["date"][5:7]) - 1) // 3 + 1)
    raise ValueError(dim)


def group_by(rows, dim):
    g = defaultdict(list)
    for r in rows:
        g[dim_value(r, dim)].append(r)
    return g


# ---------- 各 spec 的真值计算 ----------

def gt_scalar(spec):
    v = AGG[spec["agg"]](rows_in(resolve_period(spec["period"]), spec.get("filters")))
    return dict(kind="scalar", expect=round(v, 4))


def gt_group(spec):
    if spec["agg"] == "count_farm":  # 牧场计数型分组(维度表即可回答,与挤奶记录无关)
        g = defaultdict(int)
        for f in FARMS.values():
            keep = True
            for k in ("region", "scale"):
                if spec.get("filters", {}).get(k) and f[k] != spec["filters"][k]:
                    keep = False
            if keep:
                g[f[spec["dim"]]] += 1
        return dict(kind="group", dim=spec["dim"], expect=dict(g))
    g = group_by(rows_in(resolve_period(spec["period"]), spec.get("filters")), spec["dim"])
    return dict(kind="group", dim=spec["dim"], expect={k: round(AGG[spec["agg"]](v), 4) for k, v in g.items()})


def gt_trend(spec):
    rows = rows_in(resolve_period(spec["period"]), spec.get("filters"))
    key = (lambda r: r["date"][:7]) if spec["bucket"] == "month" else (lambda r: r["date"])
    g = defaultdict(list)
    for r in rows:
        g[key(r)].append(r)
    expect = {k: round(AGG[spec["agg"]](v), 4) for k, v in g.items()}
    out = dict(kind="trend", bucket=spec["bucket"], expect=expect)
    if not expect:
        out["kind"] = "empty_ok"          # 区间内本就无数据:正确答案就是空
        out["note"] = "真值区间内无任何记录,空/0 才是对的"
    return out


def gt_top(spec):
    g = group_by(rows_in(resolve_period(spec["period"]), spec.get("filters")), spec["dim"])
    ranked = sorted(((k, round(AGG[spec["agg"]](v), 4)) for k, v in g.items()),
                    key=lambda kv: (kv[1], kv[0]), reverse=(spec["dir"] != "asc"))
    return dict(kind="top", expect=[list(kv) for kv in ranked[:spec["n"]]])


def monthly_sum(year, m, filters=None):
    from cases import month as mk
    return AGG["sum_milk"](rows_in(mk(year, m), filters))


# ---------- custom 案例(每题一小段独立业务计算) ----------

def custom_fns():
    fns = {}
    def reg(name):
        def deco(fn):
            fns[name] = fn
            return fn
        return deco

    @reg("yoy_sum")  # A13 今年 vs 去年总奶量
    def _():
        ty = AGG["sum_milk"](rows_in(NAMED_PERIODS["this_year"](ANCHOR)))
        ly = AGG["sum_milk"](rows_in(NAMED_PERIODS["last_year"](ANCHOR)))
        return dict(kind="nums", expect=[round(ty, 4), round(ly, 4)],
                    note=f"今年={ty:.1f} 去年={ly:.1f},两者都出现即算如实作答")

    @reg("breed_share")  # A14 荷斯坦占泌乳牛比例
    def _():
        all_c = {r["cattle_id"] for r in FACTS}
        hs = {r["cattle_id"] for r in FACTS if CATTLE[r["cattle_id"]]["breed"] == "荷斯坦"}
        pct = len(hs) / len(all_c) * 100
        return dict(kind="any_num", expect=[round(pct, 2), round(pct / 100, 4)],
                    note=f"真值={pct:.2f}%,接受百分数或小数两种口径")

    @reg("jul_vs_aug")  # B10 7月 vs 8月
    def _():
        jul, aug = monthly_sum(2026, 7), monthly_sum(2026, 8)
        hi = "7月" if jul >= aug else "8月"
        return dict(kind="nums", expect=[round(jul, 4), round(aug, 4)],
                    note=f"{hi}更高(7月={jul:.1f}, 8月={aug:.1f});两个月数值都出现即算如实作答")

    @reg("simmental_last_month")  # C07 西门塔尔上月 产奶量+乳脂率
    def _():
        fl = dict(breed="西门塔尔")
        rows = rows_in(NAMED_PERIODS["last_month"](ANCHOR), fl)
        return dict(kind="nums",
                    expect=[round(AGG["sum_milk"](rows), 4), round(AGG["avg_fat"](rows), 4)])

    @reg("mom_drop_farm")  # D01 6月比5月下降最多的牧场
    def _():
        g5 = group_by(rows_in(resolve_period(("2026-05-01", "2026-06-01")), None), "farm")
        g6 = group_by(rows_in(resolve_period(("2026-06-01", "2026-07-01")), None), "farm")
        drop = {f: AGG["sum_milk"](g5.get(f, [])) - AGG["sum_milk"](g6.get(f, [])) for f in g5}
        farm = max(drop, key=drop.get)
        return dict(kind="label", expect=farm, note=f"下降量={drop[farm]:.1f}")

    @reg("q1_vs_q2")  # D02 二季度 vs 一季度
    def _():
        q1 = AGG["sum_milk"](rows_in(resolve_period(("2026-01-01", "2026-04-01")), None))
        q2 = AGG["sum_milk"](rows_in(resolve_period(("2026-04-01", "2026-07-01")), None))
        return dict(kind="nums", expect=[round(q1, 4), round(q2, 4)],
                    note=f"增量={q2 - q1:.1f};两季数值都出现即算如实作答")

    @reg("breed_gap")  # D03 品种产奶量极差
    def _():
        g = {k: AGG["sum_milk"](v) for k, v in group_by(FACTS, "breed").items()}
        return dict(kind="nums", expect=[round(max(g.values()), 4), round(min(g.values()), 4)],
                    note=str({k: round(v) for k, v in g.items()}))

    @reg("consec_down_farms")  # D05 最近两个完整月连续下滑的牧场
    def _():
        m6 = {k: AGG["sum_milk"](v) for k, v in group_by(rows_in(resolve_period(("2026-06-01", "2026-07-01")), None), "farm").items()}
        m7 = {k: AGG["sum_milk"](v) for k, v in group_by(rows_in(resolve_period(("2026-07-01", "2026-08-01")), None), "farm").items()}
        m8 = {k: AGG["sum_milk"](v) for k, v in group_by(rows_in(resolve_period(("2026-08-01", "2026-09-01")), None), "farm").items()}
        down = [f for f in m7 if m7.get(f, 0) < m6.get(f, 0) and m8.get(f, 0) < m7.get(f, 0)]
        if not down:
            return dict(kind="empty_ok", note="真值:没有牧场连续下滑,空/无 即正确")
        return dict(kind="labels", expect=down)

    @reg("yoy_yield")  # D06 今年 vs 去年平均单产
    def _():
        ty = AGG["avg_milk"](rows_in(NAMED_PERIODS["this_year"](ANCHOR)))
        ly = AGG["avg_milk"](rows_in(NAMED_PERIODS["last_year"](ANCHOR)))
        return dict(kind="nums", expect=[round(ty, 4), round(ly, 4)])

    @reg("milk_quality_last_month")  # E04 上月乳脂+乳蛋白
    def _():
        rows = rows_in(NAMED_PERIODS["last_month"](ANCHOR), None)
        return dict(kind="nums",
                    expect=[round(AGG["avg_fat"](rows), 4), round(AGG["avg_protein"](rows), 4)])

    @reg("barn_yield_gap")  # D08 牛舍单产极差
    def _():
        g = {k: AGG["avg_milk"](v) for k, v in group_by(FACTS, "barn").items()}
        return dict(kind="nums", expect=[round(max(g.values()), 4), round(min(g.values()), 4)],
                    note=str({k: round(v, 2) for k, v in sorted(g.items())}))

    return fns


CUSTOM = custom_fns()


def build():
    out = []
    for c in CASES:
        spec = c["spec"]
        k = spec["kind"]
        entry = dict(id=c["id"], cat=c["cat"], q=c["q"])
        if k == "scalar":
            entry.update(gt_scalar(spec))
            entry["period"] = resolve_period(spec["period"])
        elif k == "group":
            entry.update(gt_group(spec))
            entry["period"] = resolve_period(spec["period"])
        elif k == "trend":
            entry.update(gt_trend(spec))
            entry["period"] = resolve_period(spec["period"])
        elif k == "top":
            entry.update(gt_top(spec))
            entry["period"] = resolve_period(spec["period"])
        elif k == "custom":
            entry.update(CUSTOM[spec["fn"]]())
        elif k in ("no_data", "empty_ok"):
            entry["kind"] = k
        else:
            raise ValueError(k)
        out.append(entry)

    gt = dict(anchor=ANCHOR.isoformat(),
              generated_at=datetime.now().strftime("%Y-%m-%d %H:%M:%S"),
              data_window=[min(r["date"] for r in FACTS), max(r["date"] for r in FACTS)],
              n_facts=len(FACTS),
              cases=out)
    dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "gt.json")
    json.dump(gt, open(dest, "w", encoding="utf-8"), ensure_ascii=False, indent=1)
    print(f"真值已构建:{len(out)} 条 → {dest}")
    print(f"锚定日期 {ANCHOR},数据窗口 {gt['data_window']},{len(FACTS)} 条挤奶记录")


if __name__ == "__main__":
    build()
