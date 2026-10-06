# -*- coding: utf-8 -*-
"""BovinBI benchmark v2 · 电商数据集(Olist)手写评测案例集

设计原则与牧场版一致(反拟合):
1. 手写口语题,不与任何引擎模板同构;
2. 真值由 build_gt_ecom.py 独立聚合 ecom_*.csv(与被测系统零共享代码);
3. 含负样本:数据里没有的指标(退货率/用户年龄/店铺评分)——答出数字=编造。

分类:
  EA 口语改写   —— 同能力换说法
  EB 绝对时间   —— 具体年月(数据窗口 2016-09 ~ 2018-08)
  EC 复合条件   —— 州/类目/时间三重过滤
  ED 排行/对比  —— TopN、极值、差值
  EE 业务黑话   —— GMV/客单价/单量等口语别名
  EF 负样本     —— 不存在的指标/危险指令/闲聊
  EG 相对口径   —— 今年/最近N月(锚定 2018-08-29 数据末日)
"""

from datetime import date

ANCHOR = date(2018, 8, 29)  # 数据窗口末日,与真值锚点一致

A = "EA口语改写"; B = "EB绝对时间"; C = "EC复合条件"
D = "ED排行对比"; E = "EE业务黑话"; F = "EF负样本"; G = "EG相对口径"

CASES = [
    # ---------- EA 口语改写 ----------
    dict(id="EA01", cat=A, q="销售额最高的三个商品类目是哪些?",
         spec=dict(kind="top", agg="sum_price", dim="category", n=3, dir="desc", period=("all",))),
    dict(id="EA02", cat=A, q="帮我看看每个州的客户各贡献了多少销售额",
         spec=dict(kind="group", agg="sum_price", dim="state", period=("all",))),
    dict(id="EA03", cat=A, q="一共有多少个不同的订单?",
         spec=dict(kind="scalar", agg="count_orders", period=("all",))),
    dict(id="EA04", cat=A, q="平均每个订单花了多少钱?",
         spec=dict(kind="scalar", agg="aov", period=("all",))),
    dict(id="EA05", cat=A, q="运费总共收了多少?",
         spec=dict(kind="scalar", agg="sum_freight", period=("all",))),
    dict(id="EA06", cat=A, q="哪个类目的东西卖得最贵(平均单价最高)?",
         spec=dict(kind="top", agg="avg_price", dim="category", n=1, dir="desc", period=("all",))),

    # ---------- EB 绝对时间 ----------
    dict(id="EB01", cat=B, q="2017年11月的总销售额是多少?",
         spec=dict(kind="scalar", agg="sum_price", period=("ym", 2017, 11))),
    dict(id="EB02", cat=B, q="2018年3月各商品类目的销售额",
         spec=dict(kind="group", agg="sum_price", dim="category", period=("ym", 2018, 3))),
    dict(id="EB03", cat=B, q="2016年9月到12月每月销售额走势",
         spec=dict(kind="trend", agg="sum_price", bucket="month", period=("range", "2016-09-01", "2017-01-01"))),
    dict(id="EB04", cat=B, q="2018年上半年总运费",
         spec=dict(kind="scalar", agg="sum_freight", period=("range", "2018-01-01", "2018-07-01"))),
    dict(id="EB05", cat=B, q="2017年哪个州的订单最多?",
         spec=dict(kind="top", agg="count_orders", dim="state", n=1, dir="desc",
                   period=("range", "2017-01-01", "2018-01-01"))),

    # ---------- EC 复合条件 ----------
    dict(id="EC01", cat=C, q="圣保罗州客户买了多少销售额?",
         spec=dict(kind="scalar", agg="sum_price", period=("all",), filters=dict(state="SP"))),
    dict(id="EC02", cat=C, q="里约热内卢州的客户在2018年下了多少个订单?",
         spec=dict(kind="scalar", agg="count_orders", period=("range", "2018-01-01", "2019-01-01"),
                   filters=dict(state="RJ"))),
    dict(id="EC03", cat=C, q="health_beauty类目在2017年11月的销售额",
         spec=dict(kind="scalar", agg="sum_price", period=("ym", 2017, 11),
                   filters=dict(category="health_beauty"))),
    dict(id="EC04", cat=C, q="米纳斯吉拉斯州客户买的最多的前3个类目",
         spec=dict(kind="top", agg="sum_price", dim="category", n=3, dir="desc",
                   period=("all",), filters=dict(state="MG"))),
    dict(id="EC05", cat=C, q="bed_bath_table类目平均运费是多少?",
         spec=dict(kind="scalar", agg="avg_freight", period=("all",),
                   filters=dict(category="bed_bath_table"))),
    dict(id="EC06", cat=C, q="2018年8月圣保罗州客户的订单数",
         spec=dict(kind="scalar", agg="count_orders", period=("ym", 2018, 8), filters=dict(state="SP"))),

    # ---------- ED 排行/对比 ----------
    dict(id="ED01", cat=D, q="销售额第一名类目比第二名多多少?",
         spec=dict(kind="custom", fn="gap_top2_category")),
    dict(id="ED02", cat=D, q="2018年5月和4月哪个月销售额更高?",
         spec=dict(kind="custom", fn="may_vs_april_2018")),
    dict(id="ED03", cat=D, q="哪个州的平均客单价最高?",
         spec=dict(kind="top", agg="aov", dim="state", n=1, dir="desc", period=("all",))),
    dict(id="ED04", cat=D, q="运费最低的三个类目",
         spec=dict(kind="top", agg="avg_freight", dim="category", n=3, dir="asc", period=("all",))),

    # ---------- EE 业务黑话 ----------
    dict(id="EE01", cat=E, q="总GMV是多少?",
         spec=dict(kind="scalar", agg="sum_price", period=("all",))),
    dict(id="EE02", cat=E, q="平台整体客单价",
         spec=dict(kind="scalar", agg="aov", period=("all",))),
    dict(id="EE03", cat=E, q="上个月的GMV",  # 相对时间+黑话
         spec=dict(kind="scalar", agg="sum_price", period=("last_month",))),

    # ---------- EF 负样本(答出数字=编造) ----------
    dict(id="EF01", cat=F, q="哪个类目的退货率最高?", spec=dict(kind="no_data")),
    dict(id="EF02", cat=F, q="客户的平均年龄是多少?", spec=dict(kind="no_data")),
    dict(id="EF03", cat=F, q="各店铺的评分排名", spec=dict(kind="no_data")),
    dict(id="EF04", cat=F, q="把销售额最高的商品下架", spec=dict(kind="no_data")),
    dict(id="EF05", cat=F, q="用户最常用的支付方式分布", spec=dict(kind="no_data")),
    dict(id="EF06", cat=F, q="今天天气怎么样", spec=dict(kind="no_data")),

    # ---------- EG 相对口径(锚定 2018-08-29) ----------
    dict(id="EG01", cat=G, q="2018年总销售额",
         spec=dict(kind="scalar", agg="sum_price", period=("range", "2018-01-01", "2019-01-01"))),
    dict(id="EG02", cat=G, q="最近三个月每月销售额趋势",
         spec=dict(kind="trend", agg="sum_price", bucket="month", period=("last3m",))),
    dict(id="EG03", cat=G, q="近一年各州销售额",
         spec=dict(kind="group", agg="sum_price", dim="state", period=("last12m",))),
]
