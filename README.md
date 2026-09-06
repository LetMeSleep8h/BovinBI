# BovinBI · 对话式商业智能(ChatBI)

> 受腾讯开源 ChatBI 项目 [SuperSonic](https://github.com/tencentmusic/supersonic) 启发的极简复刻版:
> 用户用自然语言提问 → 系统自动完成 **时间解析 → 语义缓存 → Schema 召回 → SQL 生成(LLM+规则双引擎) → AST 级安全守护 → 只读执行 → 智能图表推荐** 的全链路。
>
> **业务域:智慧牧场·奶牛养殖**(6 牧场 · 1,200 头泌乳牛 · 36,984 条挤奶记录,合成数据集,业务规律可审计、可复现),
> **LLM 编排层使用 LangChain4j**(与 SuperSonic 同版本同结构,对齐其 ModelProvider/ModelFactory/PromptTemplate 实现),
> **零安装即可演示**(内置 H2,一条命令切 MySQL 生产形态),支持任意 OpenAI 兼容大模型(DeepSeek / 通义千问 / GLM / OpenAI),离线规则模式同样完整可用。

![tech](https://img.shields.io/badge/Java-21-blue) ![tech](https://img.shields.io/badge/Spring%20Boot-3.5.x-brightgreen) ![tech](https://img.shields.io/badge/LangChain4j-0.36.2-orange) ![tech](https://img.shields.io/badge/Vue-3.5-409eff)

## 30 秒体验

```bash
java -jar target/BovinBI-1.0.0.jar        # 或 mvn spring-boot:run
# 浏览器打开 http://localhost:8080   登录:admin / bovin123
# 试试问: 近12个月每月产奶量趋势 / 产奶量Top5牧场 / 上个月各品种产奶量占比 / 上个月产奶量环比 / 西北地区牧场的平均乳脂率
```

## 核心架构

```
用户问题 ──► ①时间解析(规则,半开区间) ──► ②语义缓存(归一化key) ──► ③Schema召回(同义词词典)
                                                                        │
前端图表 ◄── ⑥图表推荐+透视(折线/饼图/条形) ◄── ⑤SQL守护(JSqlParser AST) ◄── ④SQL生成(LLM+规则双引擎)
                │                                                              │
                └── 审计落库(query_log) / 只读连接 + queryTimeout + 强制LIMIT ──┘
```

- **双引擎**:LLM 生成失败自动降级规则引擎(9 类问题形态),演示不依赖外部 API
- **安全纵深**:单语句校验 / 表白名单 / 强制 LIMIT / 只读连接 / 8s 超时(8 个安全单测)
- **质量评测**:123 条评测集一条命令回归,规则引擎结构准确率 100%(`eval-report.md`)
- **语义层**:数据集/维度/指标/同义词/口径描述,业务黑话(奶量/头数/单产)即时生效

## 与 SuperSonic 的源码级对齐

| SuperSonic 原实现 | BovinBI 对应实现 |
| --- | --- |
| `dev.langchain4j.provider.ModelProvider` 静态注册表 | `llm/ModelProvider`(同名同职责,按 provider 路由) |
| `OpenAiModelFactory`(OpenAiChatModel.builder) | `llm/OpenAiModelFactory`(逐行对齐,同版本 0.36.2) |
| `ChatModelConfig` 模型配置 POJO | `llm/ChatModelConfig` |
| `OnePassSCSqlGenStrategy`:`#Role/#Task/#Rules/#Exemplars` 提示词 + `PromptTemplate.from(...).apply(variables)` | `prompts/nl2sql-system.md` 同结构提示词 + LangChain4j `PromptTemplate` 变量化(`#Schema/#SideInfo/#Question`) |
| `buildSideInformation`(时间/日期注入) | SideInfo 段(今天日期 + 已解析时间区间) |
| Dataset/Dimension/Metric 语义层 | Dataset/DatasetField(维度/指标/同义词/口径) |

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.5 · MyBatis-Plus · JSqlParser · Caffeine · jjwt · springdoc |
| LLM | **LangChain4j 0.36.2**(OpenAI 兼容协议,与 SuperSonic 同款 ModelProvider/ModelFactory 结构),可切换 mock 规则引擎 |
| 前端 | Vue 3 · TypeScript · Vite · Element Plus · Pinia · Vue Router · ECharts · Axios |
| 数据 | 智慧牧场·奶牛养殖合成数据集(星型模型);默认 H2,生产 MySQL 8 |

## 快速开始

### 方式一:零安装演示(默认)
```bash
mvn spring-boot:run          # 内置 H2 文件库,启动自动建表+装载数据集
```

### 方式二:接入真实大模型(LangChain4j)
```yaml
# application.yml
bovin:
  llm:
    provider: openai          # mock → openai
    base-url: https://api.deepseek.com   # 千问: https://dashscope.aliyuncs.com/compatible-mode/v1
    api-key: ${LLM_API_KEY}
    model: deepseek-chat
    max-retries: 2            # LangChain4j 内置重试
```
LLM 失败/超时自动降级规则引擎(`fallback-to-rule`),演示永不中断。

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
mvn test -Dtest=Nl2SqlEvalRunner -Dbovin.llm.provider=mock   # 123 条评测 → eval-report.md(规则引擎 100%)
mvn test -Dbovin.llm.provider=mock                           # 全部 42 个单测(安全/时间/规则引擎/图表推荐/评测)
python3 benchmark/bench_latency.py      # 延迟基准
```

## 目录结构

```
BovinBI/
├── src/main/java/com/eighthours/bovinbi/
│   ├── service/        # 六步管线:TimeRangeParser/SchemaLinker/Rule+LlmSqlGenerator/
│   │                   #           SqlGuard/QueryExecutor/ChartAdvisor/SemanticCache/Nl2SqlService
│   ├── llm/            # LangChain4j 接入(对齐 SuperSonic):ModelProvider/ModelFactory/
│   │                   #           OpenAiModelFactory/LangChain4jClient
│   ├── controller/ service/ entity/ mapper/ dto/ security/ config/ init/
├── src/main/resources/
│   ├── prompts/nl2sql-system.md   # NL2SQL 提示词(#Role/#Task/#Rules/#Exemplars,SuperSonic 同构)
│   └── dataset/dwh/*.csv          # 牧场星型模型数据(DataLoader 启动自动装载)
├── frontend/           # Vue3 + TS + Element Plus + ECharts
├── dataset/            # build_bovine_dwh.py:合成数据集生成器(固定种子,可复现)
├── eval/               # 评测集生成器;评测器在 src/test
├── sql/ docker/        # MySQL 建表脚本 + docker-compose
├── benchmark/          # 延迟基准脚本
├── archive/            # 旧版遗留(含 v1 的 UCI 电商数据集)
└── docs/               # 面试材料:数据流程/简历量化/重难点/面试宝典/学习路径/演示脚本
```

## 文档导航(面试必读)

| 文档 | 内容 |
| --- | --- |
| [docs/01-数据流程设计.md](docs/01-数据流程设计.md) | 六步管线详解 + 与 SuperSonic 的对应关系 + 扩展路线 |
| [docs/02-简历量化结果.md](docs/02-简历量化结果.md) | 可直接写进简历的量化条目(每个数字给出处和复现命令) |
| [docs/03-项目重难点.md](docs/03-项目重难点.md) | 8 个真实难点:问题→定位→方案对比→量化结果 |
| [docs/04-面试宝典.md](docs/04-面试宝典.md) | 30s/1min/3min 话术、高频 QA、系统设计变体、压力应对 |
| [docs/05-学习路径.md](docs/05-学习路径.md) | 最快上手路径:跑通→读主线6类→动手改5处→背材料 |
| [docs/06-演示脚本.md](docs/06-演示脚本.md) | 10 步现场演示脚本(含叙事话术和应急预案) |
| [dataset/README.md](dataset/README.md) | 牧场数据集:生成规则、内置业务规律、数据字典 |

## 数据说明

业务数据为 **「智慧牧场·奶牛养殖」合成数据集**(`dataset/build_bovine_dwh.py`,固定随机种子可复现):
6 个牧场 · 1,200 头泌乳牛 · 36,984 行挤奶记录(2025-09 ~ 2026-08),内置 5 条真实牧业量化规律
(品种差异 / 胎次泌乳曲线 / 泌乳阶段 / 季节热应激 / 牧场规模效应),分析问题都能"问出答案"。
旧版 UCI 电商数据集归档于 `archive/retail-uci-dataset/`。
