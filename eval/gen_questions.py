#!/usr/bin/env python3
"""生成 NL2SQL 评测集(牧场养殖口径)→ ../src/test/resources/eval/questions.csv
列: question,must_contain
must_contain 用 | 分隔多个断言;断言在"SQL归一化(小写、去空白)"后做包含匹配。
"""
import csv
import os

rows = []


def add(q, *musts):
    rows.append([q, "|".join(musts)])


METRIC = {
    "产奶量": ["sum(m.milk_yield)"],
    "平均单产": ["avg(m.milk_yield)"],
    "乳脂率": ["avg(m.fat_rate)"],
    "乳蛋白率": ["avg(m.protein_rate)"],
    "泌乳牛数": ["count(distinct m.cattle_id)"],
}
DIM_EXPR = {
    "各牧场": "f.farm_name", "各品种": "c.breed", "各地区": "f.region",
    "各牧场规模": "f.scale", "各泌乳阶段": "m.lactation_stage", "各牛舍": "c.barn",
}

# 1) 总体指标 × 时间表达(无时间→无过滤;时间区间为相对日期,断言只查指标表达式)
for m, expr in METRIC.items():
    for t in ["", "今年", "去年", "上个月", "近30天"]:
        q = f"{t}{m}" if t else f"总{m}"
        add(q, *expr)

# 2) 月度趋势
for m, expr in METRIC.items():
    for t in ["近12个月", "今年", "去年", "2026年", "近6个月"]:
        add(f"{t}每月{m}趋势", *expr, "date_format(m.record_date,'%y-%m')", "groupby1", "orderby1")
    add(f"每月{m}走势", *expr, "date_format(m.record_date,'%y-%m')")

# 3) 每日趋势
for m in ["产奶量", "泌乳牛数"]:
    for t in ["近30天", "近7天"]:
        add(f"{t}每天{m}趋势", *METRIC[m], "date_format(m.record_date,'%y-%m-%d')")

# 4) TopN
for dim_q, dim in [("牧场", "f.farm_name"), ("品种", "c.breed"), ("牛舍", "c.barn")]:
    for m, expr in METRIC.items():
        add(f"{m}Top10{dim_q}", *expr, dim, "orderby2desc", "limit10")
for t in ["今年", "上个月", "近3个月"]:
    add(f"{t}产奶量前5的牧场", "sum(m.milk_yield)", "f.farm_name", "orderby2desc", "limit5")
add("产奶量最高的10个品种", "sum(m.milk_yield)", "c.breed", "orderby2desc", "limit10")
add("乳脂率最差的5个牧场", "avg(m.fat_rate)", "f.farm_name", "orderby2asc", "limit5")

# 5) 占比/分布
for dim_q, dim in DIM_EXPR.items():
    for m in ["产奶量", "泌乳牛数"]:
        add(f"{m}{dim_q}占比", *METRIC[m], dim)
        add(f"上个月{dim_q}的{m}分布", *METRIC[m], dim)

# 6) 环比/同比
for m in ["产奶量", "泌乳牛数", "乳脂率"]:
    add(f"{m}环比", *METRIC[m])
add("上个月产奶量环比", "sum(m.milk_yield)")
add("今年产奶量同比", "sum(m.milk_yield)")

# 7) 维度汇总
add("工作日和周末的产奶量对比", "sum(m.milk_yield)", "casewhendayofweek")
add("工作日和周末的泌乳牛数对比", "count(distinct m.cattle_id)", "casewhendayofweek")
add("各季度产奶量", "sum(m.milk_yield)", "quarter(m.record_date)")
add("今年各季度乳脂率", "avg(m.fat_rate)", "quarter(m.record_date)")
add("西北地区牧场的产奶量", "sum(m.milk_yield)", "f.region='西北'")
add("大型牧场的平均单产", "avg(m.milk_yield)", "f.scale='大型'")
add("荷斯坦牛的产奶量", "sum(m.milk_yield)", "c.breed='荷斯坦'")
add("娟姗牛的乳脂率", "avg(m.fat_rate)", "c.breed='娟姗'")
add("泌乳初期的产奶量", "sum(m.milk_yield)", "m.lactation_stage='泌乳初期'")
add("泌乳后期的乳蛋白率", "avg(m.protein_rate)", "m.lactation_stage='泌乳后期'")
add("周末的产奶量", "sum(m.milk_yield)", "dayofweek(m.record_date)in(1,7)")

# 8) 组合问题(时间×维度)
add("近3个月每月各品种产奶量", "sum(m.milk_yield)", "c.breed", "date_format(m.record_date,'%y-%m')")
add("2026年每月各地区泌乳牛数", "count(distinct m.cattle_id)", "f.region", "date_format(m.record_date,'%y-%m')")
add("今年每月各泌乳阶段的产奶量", "sum(m.milk_yield)", "m.lactation_stage", "date_format(m.record_date,'%y-%m')")
add("近6个月每月大型牧场的产奶量", "sum(m.milk_yield)", "date_format(m.record_date,'%y-%m')", "f.scale='大型'")

# 去重并写出
seen, out = set(), []
for q, musts in rows:
    if q not in seen:
        seen.add(q)
        out.append([q, musts])

dest = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "test", "resources", "eval")
os.makedirs(dest, exist_ok=True)
with open(os.path.join(dest, "questions.csv"), "w", newline="", encoding="utf-8") as f:
    w = csv.writer(f)
    w.writerow(["question", "must_contain"])
    w.writerows(out)
print(f"生成评测集 {len(out)} 条 → src/test/resources/eval/questions.csv")
