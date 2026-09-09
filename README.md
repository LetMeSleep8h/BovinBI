# BovinBI · 对话式商业智能(ChatBI)

> 面向业务人员的对话式商业智能(ChatBI)平台:
> 用户用自然语言提问 → 系统自动完成 **闲聊分流 → 时间解析 → 语义缓存 → Schema 召回 → SQL 生成(双引擎降级链) → AST 级安全守护 → 只读执行 → 智能图表推荐** 的六步链路。
>
> **业务域:智慧牧场·奶牛养殖**(6 牧场 · 1,200 头泌乳牛 · 36,984 条挤奶记录,合成数据集,业务规律可审计、可复现),
> **LLM 接入基于 LangChain4j**(OpenAI 兼容协议,DeepSeek / 通义千问 / GLM / OpenAI 一行配置切换),
> **零安装即可演示**(默认 H2 + 离线规则引擎,无需 API Key),离线模式同样完整可用。

![tech](https://img.shields.io/badge/Java-21-blue) ![tech](https://img.shields.io/badge/Spring%20Boot-3.5.x-brightgreen) ![tech](https://img.shields.io/badge/LangChain4j-0.36.2-orange) ![tech](https://img.shields.io/badge/Vue-3.5-409eff)

## 30 秒体验

```bash
java -jar target/BovinBI-1.0.0.jar        # 或 mvn spring-boot:run(默认离线模式)
# 浏览器打开 http://localhost:8080   登录:admin / bovin123
# 试试问: 近12个月每月产奶量趋势 / 产奶量Top5牧场 / 上个月各品种产奶量占比 / 上个月产奶量环比 / 西北地区牧场的平均乳脂率
```

## 核心架构

```
用户问题 ──► ⓪闲聊分流 ──► ①时间解析(规则,半开区间) ──► ②语义缓存(归一化key)
                                                                   │ 未命中
                              ③Schema召回(同义词词典+表白名单) ◄────┘
                                                                   │
前端图表 ◄── ⑥图表推荐+透视(折线/饼图/条形) ◄── ⑤SQL守护(JSqlParser AST) ◄── ④SQL生成(双引擎降级链)
                │                                                            │
                └── 审计落库(query_log) / 只读连接 + queryTimeout + 强制LIMIT ──┘

④ 的降级链(LLM 模式):LLM生成 ─失败→ 规则引擎
                       LLM生成 ─守护/执行失败→ LLM自修复一次 ─仍失败→ 规则引擎
```

- **双引擎降级链**:LLM 优先,任何一环失败(生成/校验/执行)最终都落到规则引擎(9 类问题形态),演示永不中断;`engine` 字段(LLM / LLM(修复) / RULE / RULE(降级) / CACHE)让降级透明可观测
- **安全纵深**:AST 单语句校验 / 表白名单 / 强制 LIMIT / 只读连接 / 8s 超时(8 个安全单测)
- **质量评测**:123 条评测集一条命令回归,规则引擎结构准确率 100%(`eval-report.md`),60 个单元/集成测试全绿
- **语义层**:数据集/维度/指标/同义词/口径描述,业务黑话(奶量/头数/单产)即时生效

## LLM 接入设计

- `llm/LlmClient`:LLM 抽象(chat + extractJson),测试用手写替身即可,不锁死框架
- `llm/LangChain4jClient`:项目唯一 LLM 出口,懒构建 OpenAI 兼容模型实例(mock 模式零外部依赖启动)
- `prompts/nl2sql-system.md`:`#Role/#Task/#Rules/#Exemplars` 四段系统提示词 + `#Schema/#SideInfo/#Question` 变量化用户模板(LangChain4j `PromptTemplate` 填充)
- SideInfo 段注入今天日期与已解析时间区间,时间理解不依赖 LLM
- 接入步骤:`export LLM_API_KEY=sk-xxx` + `--spring.profiles.active=dev`(见 `application-dev.yml`)

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.5 · MyBatis-Plus · JSqlParser · Caffeine · jjwt · springdoc |
| LLM | **LangChain4j 0.36.2**(OpenAI 兼容协议),可切离线规则引擎 |
| 前端 | Vue 3 · TypeScript · Vite · Element Plus · Pinia · Vue Router · ECharts · Axios |
| 数据 | 智慧牧场·奶牛养殖合成数据集(星型模型);默认 H2,生产 MySQL 8 |

## 快速开始

### 方式一:零安装演示(默认,离线)
```bash
mvn spring-boot:run          # 内置 H2 文件库 + 离线规则引擎,启动自动建表+装载数据
```

### 方式二:接入真实大模型(LangChain4j)
```bash
export LLM_API_KEY=sk-xxx    # DeepSeek / 通义千问 / GLM 任一 OpenAI 兼容 Key
mvn spring-boot:run -Dspring-boot.run.profiles=dev
```
LLM 任何一环失败自动进入降级链(自修复 → 规则兜底),演示永不中断。

### 方式三:MySQL 生产形态
```bash
docker compose -f docker/docker-compose.yml up -d     # MySQL 8 @ localhost:3307
java -jar target/BovinBI-1.0.0.jar --spring.profiles.active=mysql
```

### 前端开发模式
```bash
cd frontend && npm install && npm run dev    # http://localhost:5173,代理到 8080
npm run build                                # 产物输出到后端 static,单 JAR 部署
```

## 质量与基准

```bash
mvn test                            # 60 个单元/集成测试(管线降级链/安全/时间/规则引擎/图表/缓存/评测)
mvn test -Dtest=Nl2SqlEvalRunner    # 123 条评测 → eval-report.md(规则引擎 100%)
python3 benchmark/bench_latency.py  # 延迟基准(缓存冷热对比)
```

## 目录结构

```
BovinBI/
├── src/main/java/com/eighthours/bovinbi/
│   ├── service/        # 六步管线:Nl2SqlService(编排+降级链)/TimeRangeParser/SchemaLinker/
│   │                   #   RuleSqlGenerator/LlmSqlGenerator/SqlGuard/QueryExecutor/ChartAdvisor/
│   │                   #   SemanticCache/ChitChatHandler/ChatService…
│   ├── llm/            # LangChain4j 接入:LlmClient(抽象) + LangChain4jClient(唯一出口)
│   ├── controller/ security/ config/ entity/ mapper/ dto/ init/ util/ common/
├── src/main/resources/
│   ├── prompts/nl2sql-system.md   # NL2SQL 提示词(#Role/#Task/#Rules/#Exemplars)
│   └── dataset/dwh/*.csv          # 牧场星型模型数据(DataLoader 启动自动装载)
├── frontend/           # Vue3 + TS + Element Plus + ECharts
├── dataset/            # build_bovine_dwh.py:合成数据集生成器(固定种子,可复现)
├── eval/               # 评测集生成器;评测器在 src/test
├── sql/ docker/        # MySQL 建表脚本 + docker-compose
├── benchmark/          # 延迟基准脚本
└── docs/               # 学习与面试文档(见下)
```

## 文档导航

| 文档 | 内容 |
| --- | --- |
| [docs/01-架构与数据链路.md](docs/01-架构与数据链路.md) | 六步管线 + 降级链详解、每步设计动机、精简删减记录、量化指标 |
| [docs/02-源码导读.md](docs/02-源码导读.md) | 按请求生命周期的 10 步读代码路线,逐文件职责与自问 |
| [docs/03-面试宝典.md](docs/03-面试宝典.md) | 简历量化条目、三档话术、5 个重难点故事、高频 QA |
| [docs/04-学习路径.md](docs/04-学习路径.md) | 最快上手:跑通 → 读主线 8 类 → 动手改 5 处 → 背材料 |
| [docs/05-演示脚本.md](docs/05-演示脚本.md) | 10 步现场演示脚本(叙事话术 + 应急预案) |
| [dataset/README.md](dataset/README.md) | 牧场数据集:生成规则、内置业务规律、数据字典 |

## 数据说明

业务数据为 **「智慧牧场·奶牛养殖」合成数据集**(`dataset/build_bovine_dwh.py`,固定随机种子可复现):
6 个牧场 · 1,200 头泌乳牛 · 36,984 行挤奶记录(2025-09 ~ 2026-08),内置 5 条真实牧业量化规律
(品种差异 / 胎次泌乳曲线 / 泌乳阶段 / 季节热应激 / 牧场规模效应),分析问题都能"问出答案"。
