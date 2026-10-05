# BovinBI 基准测试报告(2026-09-28)

环境:macOS arm64 · Java 21 (Corretto 21.0.11) · Maven 3.9.16 · 离线模式(H2 + 规则引擎,`BOVIN_CHAT_ENGINE=pipeline`,无 LLM API Key)
被测版本:main @ 36759a5 + 工作区 WIP(UserH 手写 CRUD 层)

## 1. NL2SQL 结构准确率(123 条评测集,`Nl2SqlEvalRunner`)

| 指标 | 结果 |
| --- | --- |
| 结构准确率(断言命中) | **123/123 = 100.0%** |
| 执行成功率(未降级且 SQL 可执行) | **100.0%** |
| 平均耗时 | **21 ms/条**(总 2532 ms) |
| 失败/降级 | 0 条 |

覆盖:指标 × 时间表达 / 月度·每日趋势 / TopN / 占比分布 / 环比同比 / 维度汇总 / 组合查询。
报告存档:`eval-rule-20260928-1705.md`

## 2. 单元/集成测试回归(`mvn test`,排除评测器)

**87 个测试全部通过,0 失败 0 错误**(管线降级链 / 安全守护 / 时间解析 / 规则引擎 / 图表推荐 / 缓存 / Agent 上下文与工具 / 多 Agent / RAG 混合召回 / LLM 网关)。

## 3. 延迟基准(`benchmark/bench_latency.py`,12 问题 × 1冷+30热,共 372 次请求)

| 场景 | P50 | P95 | 均值 |
| --- | --- | --- | --- |
| 冷启动(含 SQL 生成+守护+执行) | 24 ms | 65 ms | 49 ms |
| 热查询(语义缓存命中) | 2 ms | 4 ms | 2 ms |

- 缓存命中率:360/360 = **100%**(同一问题重复提问全命中)
- 缓存加速比(冷P50/热P50):**11.6x**
- 结果存档:`latency-20260928-1706.txt`

## 结论

离线形态(规则引擎)下:准确率 100%、全量回归绿、冷查询 P50 24ms / 热 P50 2ms,语义缓存收益 11.6 倍——可稳定支撑演示与简历量化数据。

## 未覆盖(需 LLM_API_KEY)

- LLM 引擎基准:`BOVIN_CHAT_ENGINE=agent|multi-agent` + `provider=openai`(A/B 对照脚本 `scripts/run_ab.sh`,上轮结果见 `eval-runs/AB-SUMMARY.md`,agent 引擎 100%)
- RAG 混合召回(`bovin.rag.enabled=true`)与语义缓存二次命中(`semantic-enabled=true`)的端到端效果
