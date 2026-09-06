#!/usr/bin/env python3
"""生成「智慧牧场·奶牛养殖」业务数据集(合成数据,业务规则可审计、可复现)
→ ../src/main/resources/dataset/dwh/{dim_farm,dim_cattle,fact_milk}.csv

星型模型:
  dwh_dim_farm   牧场维度(地区/规模)
  dwh_dim_cattle 牛只维度(品种/胎次/牛舍)
  dwh_fact_milk  挤奶记录事实表(产奶量/乳脂率/乳蛋白率/泌乳阶段)

内置的业务规律(供 BI 分析发现):
  1. 品种差异:荷斯坦产量最高,娟姗乳脂率最高,西门塔尔居中
  2. 胎次曲线:第2~3胎为泌乳高峰,头胎偏低,4胎回落
  3. 泌乳阶段:初期爬坡 → 中期平台 → 后期下降;干奶期无记录
  4. 季节规律:夏季(6-8月)热应激减产,冬季(12-2月)增产
  5. 牧场管理:大型牧场单产高于中型,小型最低
"""
import csv
import os
import random
from datetime import date
from typing import Optional

random.seed(42)  # 固定种子,数据可复现

OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)),
                       "..", "src", "main", "resources", "dataset", "dwh")

# ---------------- 牧场维度 ----------------
FARMS = [
    # (farm_code, farm_name, region, scale)
    ("F001", "呼和浩特金山牧场", "华北", "大型"),
    ("F002", "宁夏贺兰牧场", "西北", "大型"),
    ("F003", "陕西渭南牧场", "西北", "中型"),
    ("F004", "河北张家口牧场", "华北", "中型"),
    ("F005", "黑龙江绥化牧场", "东北", "中型"),
    ("F006", "江苏盐城牧场", "华东", "小型"),
]
FARM_COW_COUNT = {"F001": 280, "F002": 260, "F003": 200, "F004": 180, "F005": 170, "F006": 110}

BREEDS = ["荷斯坦", "西门塔尔", "娟姗"]
BREED_WEIGHTS = [0.70, 0.16, 0.14]
PARITY_WEIGHTS = {1: 0.26, 2: 0.30, 3: 0.24, 4: 0.20}

# 品种基准(千克/日)
BREED_BASE = {"荷斯坦": 30.0, "西门塔尔": 21.0, "娟姗": 24.5}
BREED_FAT = {"荷斯坦": 3.9, "西门塔尔": 4.2, "娟姗": 5.2}      # 乳脂率 %
BREED_PROTEIN = {"荷斯坦": 3.25, "西门塔尔": 3.40, "娟姗": 3.80}  # 乳蛋白率 %
PARITY_FACTOR = {1: 0.86, 2: 1.00, 3: 1.02, 4: 0.94}
STAGE_FACTOR = {"泌乳初期": 0.92, "泌乳中期": 1.00, "泌乳后期": 0.72}
FARM_FACTOR = {"大型": 1.06, "中型": 1.00, "小型": 0.93}

# 记录日期:每月 5/15/25 号采样,2025-09 ~ 2026-08(相对"上个月"演示友好)
MONTHS = [(2025, m) for m in range(9, 13)] + [(2026, m) for m in range(1, 9)]
SAMPLE_DAYS = [5, 15, 25]
CYCLE = 14  # 泌乳周期 = 12 个泌乳月 + 2 个干奶月


def stage_of(pos: int) -> Optional[str]:
    """泌乳周期位置 → 泌乳阶段;干奶期返回 None(无挤奶记录)"""
    if pos >= 12:
        return None
    if pos <= 2:
        return "泌乳初期"
    if pos <= 7:
        return "泌乳中期"
    return "泌乳后期"


def season_factor(year: int, month: int) -> float:
    if month in (6, 7, 8):
        return 0.88   # 夏季热应激
    if month in (12, 1, 2):
        return 1.06   # 冬季舒适增产
    return 1.0


def main():
    os.makedirs(OUT_DIR, exist_ok=True)

    with open(os.path.join(OUT_DIR, "dim_farm.csv"), "w", newline="", encoding="utf-8") as fp:
        w = csv.writer(fp)
        w.writerow(["id", "farm_code", "farm_name", "region", "scale"])
        for i, (code, name, region, scale) in enumerate(FARMS, start=1):
            w.writerow([i, code, name, region, scale])

    cow_id = 0
    cows = []  # (cow_id, code, breed, parity, barn, farm_id, cycle_offset)
    for fi, (fcode, _, _, _) in enumerate(FARMS, start=1):
        for seq in range(1, FARM_COW_COUNT[fcode] + 1):
            cow_id += 1
            breed = random.choices(BREEDS, weights=BREED_WEIGHTS)[0]
            parity = random.choices(list(PARITY_WEIGHTS), weights=list(PARITY_WEIGHTS.values()))[0]
            barn = f"牛舍{chr(ord('A') + random.randint(0, 5))}"
            cows.append((cow_id, f"{fcode}-C{seq:04d}", breed, parity, barn, fi,
                         random.randint(0, CYCLE - 1)))

    with open(os.path.join(OUT_DIR, "dim_cattle.csv"), "w", newline="", encoding="utf-8") as fp:
        w = csv.writer(fp)
        w.writerow(["id", "cattle_code", "breed", "parity", "barn", "farm_id"])
        w.writerows(c[:6] for c in cows)

    fact_id = 0
    total_yield = fat_sum = protein_sum = 0.0
    stage_stat = {"泌乳初期": 0, "泌乳中期": 0, "泌乳后期": 0}
    with open(os.path.join(OUT_DIR, "fact_milk.csv"), "w", newline="", encoding="utf-8") as fp:
        w = csv.writer(fp)
        w.writerow(["id", "record_date", "cattle_id", "farm_id",
                    "milk_yield", "fat_rate", "protein_rate", "lactation_stage"])
        for m_idx, (year, month) in enumerate(MONTHS):
            season = season_factor(year, month)
            for (cid, _, breed, parity, _, farm_i, offset) in cows:
                stage = stage_of((offset + m_idx) % CYCLE)
                if stage is None:
                    continue  # 干奶期,无记录
                for day in SAMPLE_DAYS:
                    yield_kg = (BREED_BASE[breed] * PARITY_FACTOR[parity]
                                * STAGE_FACTOR[stage] * season * FARM_FACTOR[FARMS[farm_i - 1][3]]
                                * random.gauss(1.0, 0.04))
                    yield_kg = max(5.0, round(yield_kg, 1))
                    fat = round(min(6.2, max(3.0, BREED_FAT[breed]
                                             - (yield_kg - 27) * 0.02 + random.gauss(0, 0.12))), 2)
                    protein = round(min(4.5, max(2.8, BREED_PROTEIN[breed]
                                                 - (yield_kg - 27) * 0.008 + random.gauss(0, 0.07))), 2)
                    fact_id += 1
                    total_yield += yield_kg
                    fat_sum += fat
                    protein_sum += protein
                    stage_stat[stage] += 1
                    w.writerow([fact_id, f"{year}-{month:02d}-{day:02d}", cid, farm_i,
                                yield_kg, fat, protein, stage])

    print(f"生成完成 → {OUT_DIR}")
    print(f"  牧场 {len(FARMS)} 个, 牛只 {cow_id} 头, 挤奶记录 {fact_id} 行")
    print(f"  平均单产 {total_yield / fact_id:.1f} kg, 平均乳脂率 {fat_sum / fact_id:.2f}%, "
          f"平均乳蛋白率 {protein_sum / fact_id:.2f}%")
    print(f"  泌乳阶段分布 {stage_stat}")
    # 与原电商数据集量级对齐(42,006 行),便于横向比较
    assert fact_id > 25000, "数据量异常"


if __name__ == "__main__":
    main()
