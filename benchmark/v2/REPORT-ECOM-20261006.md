# BovinBI benchmark v2 · 电商数据集(Olist)评测 + 优化前后对比(2026-10-06)

> 首次以电商真实数据(110,197 行明细 / 96,478 订单 / 2016-09~2018-08)为对象建评测:
> 33 道手写题(七类含负样本)+ 独立真值(纯 Python 聚合 CSV,零共享代码)。
> 本文件记录完整闭环:**建评测 → 双引擎基线 → 归因 → 实施优化 → 复测验证前后差异 → 后续思路**。

## 一、评测资产(新增)

| 文件 | 内容 |
| --- | --- |
| `cases_ecom.py` | 33 题:口语改写6 / 绝对时间5 / 复合条件6 / 排行对比4 / 黑话3 / **负样本6** / 相对口径3 |
| `build_gt_ecom.py` | 真值独立聚合 ecom CSV(锚定数据末日 2018-08-29)→ `gt-ecom.json` |
| `run_bench.py` | 新增 `--dataset`(按名选数据集)与 `--engine`(切引擎)——电商评测一条命令 |

## 二、双引擎基线(BEFORE)

| 指标 | 🐍 Python | ☕ Java |
| --- | --- | --- |
| 答对率 | 26/33 = 78.8% | 27/33 = 81.8% |
| 编造(FABRICATE) | 0 | 0 |
| 延迟 P50 | 6.2s | 5.8s |

## 三、逐题归因(Python 7 题 WRONG)

| 题 | 现象 | 根因归类 |
| --- | --- | --- |
| EA02 各州销售额 | 返回仅 2 行(27 州) | **①预览截断**:agent_node 从 executeSql 的"前8行预览"解析 rows,group 类被截 |
| EB02 类目销售额 | 同上缺桶 | ①同 |
| EC04 MG州Top3类目 | 第1/2名互换 | ①相关(数据不完整致排序偶差) |
| ED01 一二名差值 | 只返回原值未算差 | ②推理遗漏:LLM 未执行最后一步算术 |
| ED04 运费最低Top3 | 排序近似但差 | ②/口径:低频类目样本少,avg 波动大 |
| EG02/EG03 相对时间 | 桶错位 | ③**锚点口径**(已知评测偏差):真值锚 2018-08-29 vs 系统时钟 2026-10,"最近三个月"两套口径) |

## 四、实施的优化与前后对比(Python 引擎)

| 优化 | 改动 | 效果 |
| --- | --- | --- |
| **A. 全量数据回传** | agent_node 的 rows 不再解析 executeSql 预览,改调 **exportReport 工具拿完整 CSV**(_parse_csv) | EA02/EB02/EC04 由 WRONG→PASS |
| **B. 危险指令拒答** | AGENT_SYSTEM 增规则5/6:危险指令拒绝且禁止为其查数;不存在的指标禁止冒充 | (首轮复测 EF04 曾翻为 FABRICATE,加规则后恢复拒答——fallback 提示"本系统只读") |

```
BEFORE: PASS=26/33(78%) WRONG=7  P50=6184ms
AFTER : PASS=29/33(87%) WRONG=4  P50=6084ms   (+9pp,逐题翻转:EA02 EB02 EC04)
```

剩余 4 题 WRONG:2 题锚点口径(③,评测环境问题)+ 2 题 LLM 推理遗漏(②)。

## 五、后续优化思路(按优先级,含预期收益)

1. **锚点口径对齐(P0,评测环境)**:给系统加"数据窗口感知"——SideInfo 注入数据集的
   min/max 日期,相对时间解析超出窗口时自动截断到窗口末(如"最近三个月"→2018-05~08),
   而非按今天硬算。预期:EG02/EG03 及牧场评测 5 题重叠失败全部修复,Python→93%。
2. **负样本守门固化到 Java(P1)**:牧场横评 Java 3 编造 vs Python 0——Python 的 prompt
   规则5/6 同步进 Java AgentConfig 系统提示词;更硬的做法是执行前校验
   "问题含不存在指标关键词→直接 fallback"(规则层兜底,不依赖模型自觉)。
3. **差值/极值类两步推理(P2)**:ED01 类"第一名比第二名多多少"LLM 常只返回原值——
   ReAct 提示词加"对比类必须输出最终计算值";或加一个 compute 工具让模型显式做算术。
4. **低频类目的 avg 口径(P3)**:ED04 类问题在样本<30 的类目上 avg 不稳定——
   真值与引擎统一"样本量下限过滤"口径(双方都加 HAVING COUNT(*)>=30),属评测口径协商。

## 六、复现

```bash
python3 benchmark/v2/build_gt_ecom.py
python3 benchmark/v2/run_bench.py --engine python --dataset Olist --gt benchmark/v2/gt-ecom.json
python3 benchmark/v2/run_bench.py --engine java   --dataset Olist --gt benchmark/v2/gt-ecom.json
# 前后对比文件:results-ecom-python-BEFORE.json / -AFTER2.json / results-ecom-java-BEFORE.json
```
