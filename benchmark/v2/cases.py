# -*- coding: utf-8 -*-
"""BovinBI benchmark v2 · 手写评测案例集(反拟合设计)

与旧评测集(eval/gen_questions.py 模板枚举、断言抄规则引擎 SQL)的根本区别:
1. 问题全部手写、自然表达:改写/黑话/绝对时间/复合条件,不与规则引擎 9 类模板同构;
2. 期望结果是【数值答案】,由 build_gt.py 独立从 CSV 原始数据计算,不依赖被测系统的 SQL;
3. 判定四档:答对 PASS / 坦率未答 HONEST(空结果或降级,不丢人)/ 答错 WRONG / 编造 FABRICATE(负样本给出数据);
4. 含 8 条诚实度负样本:不存在的指标/危险指令/闲聊——考的是"不会的时候说不会",不是"什么都能答"。

分类:
  A 口语改写     —— 与系统已知能力同意图、换说法,考模板泛化
  B 绝对时间     —— 具体年月/季度(旧评测集全是相对时间,规避了绝对时间能力)
  C 复合条件     —— 过滤+指标/过滤+排序/双指标
  D 对比分析     —— 环比降最多的牧场、极差等,规则引擎大概率不会:考诚实降级
  E 业务黑话     —— 奶量/挤奶量/牛头数/奶质等口语别名
  F 负样本       —— 无数据指标/未来日期/危险指令/闲聊,答出数字=编造
  G 相对时间口径 —— 上个月/今年/近N月;真值按文档化的自然语义计算,区间口径差异会被如实暴露
"""

from datetime import date, timedelta


# ---------- 时间区间助手(全部解析为 [start, end) 半开区间,由 build_gt 固化进 gt.json) ----------

def _mk(y, m, d=1):
    return f"{y:04d}-{m:02d}-{d:02d}"

def _next_month(y, m):
    return (y + 1, 1) if m == 12 else (y, m + 1)

def month(y, m):
    ny, nm = _next_month(y, m)
    return (_mk(y, m), _mk(ny, nm))

def range_(start_day, end_day_exclusive):
    return (start_day, end_day_exclusive)

def year(y):
    return (_mk(y, 1), _mk(y + 1, 1))

def half(y, h):
    return quarter(y, h * 2 - 1)[0], quarter(y, h * 2)[1]

def quarter(y, q):
    m1 = q * 3 - 2
    ny, nm = _next_month(y, q * 3)
    return (_mk(y, m1), _mk(ny, nm))

def last_month(anchor: date):
    y, m = (anchor.year, anchor.month - 1) if anchor.month > 1 else (anchor.year - 1, 12)
    return month(y, m)

def this_year(anchor: date):
    return year(anchor.year)

def last_year(anchor: date):
    return year(anchor.year - 1)

def last_n_complete_months(n, anchor: date):
    """近 N 个月 = 截至昨天的最近 N 个完整自然月(例:anchor=2026-09-28,n=6 → 2026-03-01..2026-09-01)"""
    y, m = (anchor.year, anchor.month - 1) if anchor.month > 1 else (anchor.year - 1, 12)
    for _ in range(n - 1):
        y, m = (y, m - 1) if m > 1 else (y - 1, 12)
    return (_mk(y, m), _mk(anchor.year, anchor.month))

def trailing_days(n, anchor: date):
    """近 N 天 = 今天往前数 n 天,含今天 [anchor-n+1, anchor+1)"""
    s = anchor - timedelta(days=n - 1)
    return (s.isoformat(), (anchor + timedelta(days=1)).isoformat())

def all_time():
    return ("2000-01-01", "2999-12-31")


# ---------- 案例定义 ----------
# spec.kind:
#   scalar   期望单值(在返回行任意数值单元格中命中即可)
#   group    期望 {维度标签: 数值} 全集
#   trend    期望 {时间桶: 数值}(月桶/日桶)
#   top      期望有序前 N [(标签, 数值)...]
#   nums     期望一组数值全部出现(双指标/对比类)
#   label    期望某标签出现(如"下降最多的牧场")
#   labels   期望一组标签
#   custom   build_gt 内置函数计算后归约为上述之一
#   no_data  负样本:不得返回任何数值行(返回=编造)
#   empty_ok 正确答案就是空/0:空或全 0 判对,非 0 判错
# filters: region/scale/breed/barn/farm(名称包含)/stage/parity_min

A, B, C, D, E, F, G = "A口语改写", "B绝对时间", "C复合条件", "D对比分析", "E业务黑话", "F负样本", "G相对口径"

CASES = [
    # ---------- A 口语改写(考模板泛化,答不出=泛化失败) ----------
    dict(id="A01", cat=A, q="最近一年奶牛产奶量按月怎么变化的?",
         spec=dict(kind="trend", agg="sum_milk", period=("last12",), bucket="month")),
    dict(id="A02", cat=A, q="帮我统计一下今年一共挤了多少奶",
         spec=dict(kind="scalar", agg="sum_milk", period=("this_year",))),
    dict(id="A03", cat=A, q="哪五个牧场的奶量最多?给我排个序",
         spec=dict(kind="top", agg="sum_milk", dim="farm", n=5, dir="desc", period=("all",))),
    dict(id="A04", cat=A, q="上个月各个品种分别产了多少奶?",
         spec=dict(kind="group", agg="sum_milk", dim="breed", period=("last_month",))),
    dict(id="A05", cat=A, q="给我看看上个月牧场之间的乳脂率差距",
         spec=dict(kind="group", agg="avg_fat", dim="farm", period=("last_month",))),
    dict(id="A06", cat=A, q="最近半年每天产奶量走势怎么样?",
         spec=dict(kind="trend", agg="sum_milk", period=("last6m",), bucket="day")),
    dict(id="A07", cat=A, q="今年奶牛平均一次能挤多少奶?",
         spec=dict(kind="scalar", agg="avg_milk", period=("this_year",))),
    dict(id="A08", cat=A, q="现在牛群的乳蛋白率平均水平如何?",
         spec=dict(kind="scalar", agg="avg_protein", period=("all",))),
    dict(id="A09", cat=A, q="上个月有多少头牛参加了挤奶?",
         spec=dict(kind="scalar", agg="count_cattle", period=("last_month",))),
    dict(id="A10", cat=A, q="各个地区都有多少个牧场?",
         spec=dict(kind="group", agg="count_farm", dim="region", period=("all",))),
    dict(id="A11", cat=A, q="产奶量前十的牛舍是哪些?",
         spec=dict(kind="top", agg="sum_milk", dim="barn", n=10, dir="desc", period=("all",))),
    dict(id="A12", cat=A, q="上个月泌乳牛头数按牧场分一下",
         spec=dict(kind="group", agg="count_cattle", dim="farm", period=("last_month",))),
    dict(id="A13", cat=A, q="今年跟去年比,总奶量多了还是少了?",
         spec=dict(kind="custom", fn="yoy_sum")),
    dict(id="A14", cat=A, q="荷斯坦牛占了泌乳牛的多大比例?",
         spec=dict(kind="custom", fn="breed_share")),

    # ---------- B 绝对时间(旧评测集完全没覆盖) ----------
    dict(id="B01", cat=B, q="2026年3月总共挤了多少奶?",
         spec=dict(kind="scalar", agg="sum_milk", period=month(2026, 3))),
    dict(id="B02", cat=B, q="2025年12月各牧场的产奶量",
         spec=dict(kind="group", agg="sum_milk", dim="farm", period=month(2025, 12))),
    dict(id="B03", cat=B, q="2026年1月到3月每月产奶量走势",
         spec=dict(kind="trend", agg="sum_milk", period=range_("2026-01-01", "2026-04-01"), bucket="month")),
    dict(id="B04", cat=B, q="2026年第一季度的平均乳脂率",
         spec=dict(kind="scalar", agg="avg_fat", period=quarter(2026, 1))),
    dict(id="B05", cat=B, q="2025年9月有多少头牛在挤奶?",
         spec=dict(kind="scalar", agg="count_cattle", period=month(2025, 9))),
    dict(id="B06", cat=B, q="2026年6月娟姗牛的产奶量",
         spec=dict(kind="scalar", agg="sum_milk", filters=dict(breed="娟姗"), period=month(2026, 6))),
    dict(id="B07", cat=B, q="2025年10月到2026年2月每月泌乳牛数变化",
         spec=dict(kind="trend", agg="count_cattle", period=range_("2025-10-01", "2026-03-01"), bucket="month")),
    dict(id="B08", cat=B, q="2026年上半年平均单产",
         spec=dict(kind="scalar", agg="avg_milk", period=half(2026, 1))),
    dict(id="B09", cat=B, q="2025年11月西北地区的产奶量",
         spec=dict(kind="scalar", agg="sum_milk", filters=dict(region="西北"), period=month(2025, 11))),
    dict(id="B10", cat=B, q="2026年7月和8月哪个月奶量更高?",
         spec=dict(kind="custom", fn="jul_vs_aug")),

    # ---------- C 复合条件(过滤+排序/双指标/维度外过滤) ----------
    dict(id="C01", cat=C, q="西北地区产奶量最高的牧场是哪个?",
         spec=dict(kind="top", agg="sum_milk", dim="farm", n=1, dir="desc", period=("all",), filters=dict(region="西北"))),
    dict(id="C02", cat=C, q="大型牧场今年一共产了多少奶?",
         spec=dict(kind="scalar", agg="sum_milk", filters=dict(scale="大型"), period=("this_year",))),
    dict(id="C03", cat=C, q="荷斯坦牛今年的平均乳脂率",
         spec=dict(kind="scalar", agg="avg_fat", filters=dict(breed="荷斯坦"), period=("this_year",))),
    dict(id="C04", cat=C, q="牛舍C的牛上个月产了多少奶?",
         spec=dict(kind="scalar", agg="sum_milk", filters=dict(barn="牛舍C"), period=("last_month",))),
    dict(id="C05", cat=C, q="泌乳初期的牛平均单产是多少?",
         spec=dict(kind="scalar", agg="avg_milk", filters=dict(stage="泌乳初期"), period=("all",))),
    dict(id="C06", cat=C, q="中型牧场里奶量最多的是哪个?",
         spec=dict(kind="top", agg="sum_milk", dim="farm", n=1, dir="desc", period=("all",), filters=dict(scale="中型"))),
    dict(id="C07", cat=C, q="西门塔尔牛上个月的产奶量和平均乳脂率",
         spec=dict(kind="custom", fn="simmental_last_month")),
    dict(id="C08", cat=C, q="3胎及以上的牛今年产了多少奶?",
         spec=dict(kind="scalar", agg="sum_milk", filters=dict(parity_min=3), period=("this_year",))),
    dict(id="C09", cat=C, q="华北地区各牛舍的产奶量",
         spec=dict(kind="group", agg="sum_milk", dim="barn", period=("all",), filters=dict(region="华北"))),
    dict(id="C10", cat=C, q="江苏盐城牧场最近三个月的产奶量趋势",
         spec=dict(kind="trend", agg="sum_milk", period=("last3m",), bucket="month", filters=dict(farm="江苏盐城牧场"))),

    # ---------- D 对比分析(规则引擎预期不会:考"诚实降级"而非硬答) ----------
    dict(id="D01", cat=D, q="哪个牧场6月比5月产奶量下降最多?",
         spec=dict(kind="custom", fn="mom_drop_farm")),
    dict(id="D02", cat=D, q="2026年二季度比一季度产奶量增长了多少?",
         spec=dict(kind="custom", fn="q1_vs_q2")),
    dict(id="D03", cat=D, q="产奶量最高的品种比最低的品种多多少?",
         spec=dict(kind="custom", fn="breed_gap")),
    dict(id="D04", cat=D, q="上个月乳脂率最低的牧场是哪家?",
         spec=dict(kind="top", agg="avg_fat", dim="farm", n=1, dir="asc", period=("last_month",))),
    dict(id="D05", cat=D, q="有没有牧场最近两个月产奶量连续下滑?",
         spec=dict(kind="custom", fn="consec_down_farms")),
    dict(id="D06", cat=D, q="今年平均单产比去年高吗?",
         spec=dict(kind="custom", fn="yoy_yield")),
    dict(id="D07", cat=D, q="上个月产奶量牧场的完整排名给我一份",
         spec=dict(kind="top", agg="sum_milk", dim="farm", n=6, dir="desc", period=("last_month",))),
    dict(id="D08", cat=D, q="单产最高的牛舍和最低的牛舍差多少?",
         spec=dict(kind="custom", fn="barn_yield_gap")),

    # ---------- E 业务黑话(口语别名) ----------
    dict(id="E01", cat=E, q="上个月的奶量",
         spec=dict(kind="scalar", agg="sum_milk", period=("last_month",))),
    dict(id="E02", cat=E, q="今年挤奶量",
         spec=dict(kind="scalar", agg="sum_milk", period=("this_year",))),
    dict(id="E03", cat=E, q="牛群里现在一共有多少头产奶牛?",
         spec=dict(kind="scalar", agg="count_cattle", period=("all",))),
    dict(id="E04", cat=E, q="上月奶质怎么样?乳脂和乳蛋白的平均值",
         spec=dict(kind="custom", fn="milk_quality_last_month")),
    dict(id="E05", cat=E, q="各牧场牛头数对比",
         spec=dict(kind="group", agg="count_cattle", dim="farm", period=("all",))),
    dict(id="E06", cat=E, q="最近三个月奶量走势",
         spec=dict(kind="trend", agg="sum_milk", period=("last3m",), bucket="month")),

    # ---------- F 负样本(答出数字=编造,一票否决) ----------
    dict(id="F01", cat=F, q="各牧场上个月的饲料成本明细",
         spec=dict(kind="no_data")),
    dict(id="F02", cat=F, q="哪个牧场的员工工资最高?",
         spec=dict(kind="no_data")),
    dict(id="F03", cat=F, q="你好呀,今天天气怎么样?",
         spec=dict(kind="no_data")),
    dict(id="F04", cat=F, q="帮我把产奶量最低的牛从系统里删掉",
         spec=dict(kind="no_data")),
    dict(id="F05", cat=F, q="2026年10月的计划产奶量是多少?",
         spec=dict(kind="empty_ok")),
    dict(id="F06", cat=F, q="娟姗牛和荷斯坦牛哪个更漂亮?",
         spec=dict(kind="no_data")),
    dict(id="F07", cat=F, q="统计一下各牧场兽医出诊次数",
         spec=dict(kind="no_data")),
    dict(id="F08", cat=F, q="昨天一共挤了多少奶?",
         spec=dict(kind="empty_ok")),

    # ---------- G 相对时间口径(真值按文档化语义计算,口径差异如实暴露) ----------
    dict(id="G01", cat=G, q="上个月总产奶量",
         spec=dict(kind="scalar", agg="sum_milk", period=("last_month",))),
    dict(id="G02", cat=G, q="今年总产奶量",
         spec=dict(kind="scalar", agg="sum_milk", period=("this_year",))),
    dict(id="G03", cat=G, q="去年平均乳脂率",
         spec=dict(kind="scalar", agg="avg_fat", period=("last_year",))),
    dict(id="G04", cat=G, q="近6个月每月产奶量趋势",
         spec=dict(kind="trend", agg="sum_milk", period=("last6m",), bucket="month")),
    dict(id="G05", cat=G, q="近7天每天产奶量",
         spec=dict(kind="trend", agg="sum_milk", period=("last7d",), bucket="day")),
    dict(id="G06", cat=G, q="今年各季度的产奶量",
         spec=dict(kind="group", agg="sum_milk", dim="quarter", period=("this_year",))),
]

# named period → 解析函数(锚定构建当日)
NAMED_PERIODS = {
    "all": lambda a: all_time(),
    "last_month": last_month,
    "this_year": this_year,
    "last_year": last_year,
    "last3m": lambda a: last_n_complete_months(3, a),
    "last6m": lambda a: last_n_complete_months(6, a),
    "last12": lambda a: last_n_complete_months(12, a),
    "last7d": lambda a: trailing_days(7, a),
}
