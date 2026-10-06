# BovinBI benchmark v2 · 双 AI 引擎对比报告(2026-10-06)

> 首次同题双引擎横评:同一 62 题手写评测集、同一真值(独立聚合 36,984 条原始 CSV)、
> 同一运行栈(DeepSeek chat),仅切换提问引擎(`--engine python / java`)。
> 这是本项目"多引擎共存是为了 A/B 评测"这一设计立场的首次兑现。

## 总览

| 指标 | 🐍 Python(LangGraph ReAct) | ☕ Java(Agent 工具循环) | 离线规则(旧基线 2026-09-28) |
| --- | --- | --- | --- |
| **答对率(严格)** | **54/62 = 87.1%** | 43/62 = 69.4% | 28/62 = 45.2% |
| 答错(WRONG) | 7 | 10 | 20 |
| **编造(FABRICATE)** | **0** | **3** ⚠️ | 5 |
| 坦率未答(HONEST) | 0 | 6 | 9 |
| 异常 | 1(超时) | 0 | 0 |
| 延迟 P50 / 均值 | 7,071ms / 21,137ms | 6,787ms / 6,361ms | 20ms / 26ms |

## 三个关键发现

### 1. Python 引擎全面领先(87% vs 69%)

- **绝对时间题满分**:Python 10/10,Java 多数败在同一类——"上个月"等相对时间按系统当天
  (2026-10-06)解析,落在数据窗口(至 2026-08-25)之外/错位;Python 的 LLM 侧时间理解
  更贴近题意。两引擎有 5 题重叠失败(A01/A09/C10/E06/G04),**全部是这类锚点口径问题**
  ——真值锚定 2026-08-25(数据末日),评测机系统时间是 2026-10-06,口径天然错位;
  这是评测环境的已知偏差而非引擎能力差,修复口径(改系统时间或锚当日真值)后两者都会涨。
- **复合条件/对比分析**:Python 90%/88%,LLM 的组合推理优于 Java 侧模板化时间解析。

### 2. 诚实度差异是最有价值的信号

**Java 3 题 FABRICATE(编造)**:问"员工工资最高""兽医出诊次数"这类**不存在的指标**,
Java Agent 仍拼出了一张数(拿泌乳牛数冒充出诊次数);Python **0 编造**——ReAct 循环里
模型先调 getSchema 发现没有相关字段,选择空手/说明而非硬凑。
负样本守门(不存在的指标宁可不答)是生产级 Agent 的底线,**这一项比准确率更重要**。

### 3. 延迟代价透明

Python 均值 21s(ReAct 多轮 LLM 往返)vs Java 6s(Agent 循环更紧凑)vs 规则 26ms。
语义缓存命中后两引擎同为毫秒级——慢在"想",不在"查"。

## 结论与行动

- **默认引擎选 Python 的立场获得数据支持**:更高准确率 + 零编造;延迟劣势由语义缓存部分抵消。
- **Java 侧修复优先级**:① 负样本守门(编造>答错,一票否决级);② 相对时间的"数据窗口
  感知"(超出窗口自动截断到窗口末,而非按今天硬算)。
- 评测基建:`build_gt` 已修复空集除零 + 锚点固定为数据末日(`BENCH_ANCHOR` 可覆盖),
  `run_bench` 新增 `--engine` 参数——引擎横评从此一条命令。

## 复现

```bash
python3 benchmark/v2/build_gt.py
python3 benchmark/v2/run_bench.py --engine python   # → results-python-engine.json
python3 benchmark/v2/run_bench.py --engine java     # → results-java-engine.json
python3 benchmark/v2/compare.py results-baseline.json results.json
```
