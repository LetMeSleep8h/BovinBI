# BovinBI · 对话式商业智能(ChatBI)

> 面向业务人员的对话式商业智能(ChatBI)平台:
> 用户用自然语言提问 → 系统自动完成 **意图分流(LLM 优先) → 时间解析 → 语义缓存 → Schema 召回 → SQL 生成(双引擎降级链) → AST 级安全守护 → 只读执行 → 智能图表推荐** 的链路。
>
> **业务域:巴西电商 Olist 真实数据**(Kaggle 公开数据集:96,478 笔已送达订单 · 110,197 行订单明细 · 32,216 个商品 · 2016-2018,金额单位雷亚尔 BRL;
> 另含合成牧场数据集与 OWID/FAOSTAT 全球牛奶产量数据集,共三套可切换),
> **双 AI 引擎**:☕Java(LangChain4j,OpenAI 兼容协议)+ 🐍Python(FastAPI + LangChain + LangGraph,
> 工具经 MCP 回环 Java 底座),提问时自由切换;**一条命令起全栈**(docker compose 起 MySQL + pgvector + 后端 + Python Agent),
> 离线规则模式无需 API Key 同样可用。

![tech](https://img.shields.io/badge/Java-21-blue) ![tech](https://img.shields.io/badge/Spring%20Boot-3.5.x-brightgreen) ![tech](https://img.shields.io/badge/LangChain4j-0.36.2-orange) ![tech](https://img.shields.io/badge/LangGraph-0.5.x-ff69b4) ![tech](https://img.shields.io/badge/FastAPI-0.115-009688) ![tech](https://img.shields.io/badge/Vue-3.5-409eff)

## 30 秒体验

```bash
./docker/start-all.sh          # 一键全栈(见下),或 java -jar target/BovinBI-1.0.0.jar
# 浏览器打开 http://localhost:8080   登录:admin / bovin123
# 试试问: 销售额Top10商品类目 / 各客户州销售额 / 每月销售额趋势 / 总销售额是多少 / 各商品类目订单数
```

## 核心架构

```
用户问题 ──► ⓪意图分流(rag1/pgvector 向量召回;配 LLM Key 后由 LLM 判别任意口语)
                │ 取数
        ①时间解析(规则,半开区间) ──► ②语义缓存(归一化key)
                │ 未命中
        ③Schema召回(同义词词典+表白名单) ◄────┘
                │
        ④SQL 生成(双 AI 引擎,提问时切换):
          ☕Java 引擎: pipeline(LLM+自修复+规则兜底) / agent(工具循环) /
                      multi-agent(SQL→审查→修复) / orchestra(Supervisor+声明式工作流)
          🐍Python 引擎: LangGraph StateGraph — intent 路由 → ReAct Agent
                      (langchain-mcp-adapters 直连底座 /mcp 工具)
                │
前端图表 ◄── ⑥图表推荐+透视 ◄── ⑤SQL守护(JSqlParser AST,两引擎同一道闸)
                │
        SSE 实时工作流(意图/工具调用/执行逐步上屏) + 审计落库(query_log)
```

- **双 AI 引擎**:提问时 ☕/🐍 自由切换;Python 只做"智力",执行与数据永远在 Java(守护/表白名单/强制 LIMIT 不外流);Python 不可达自动降级 Java 引擎
- **意图判定 LLM 优先**:任意口语问法(你能干什么/怎么玩/在么)由 LLM 判别闲聊与取数,离线自动退回向量召回+关键词
- **安全纵深**:AST 单语句校验 / 表白名单 / 强制 LIMIT / 只读连接 / 执行超时;批量导入三道闸;执行前可要求"每一步过问"(权限模式 AUTO/STEP 每用户独立)
- **可观测**:SSE 实时 AI 工作流时间线 / 查询审计(普通用户只见自己,ADMIN 全量) / 每用户每日 token 用量独立存储
- **MCP 工具统一管理**:本地工具(getSchema/executeSql/导入导出)与外部 MCP 客户端、Python Agent 同一注册中心、同一执行入口

## LLM 接入设计

- **Java 侧**:`llm/LangChain4jClient` 项目唯一 LLM 出口(token 计量钩子);`export LLM_API_KEY=sk-xxx` + `BOVIN_LLM_PROVIDER=openai` 即点亮(DeepSeek/通义/GLM 任一 OpenAI 兼容 Key)
- **Python 侧**:`python-agent` 容器传 `LLM_API_KEY` 即点亮;LangGraph ReAct Agent 经 MCP 自主调工具,意图路由/降级/兜底是图节点
- 两侧任何一环失败自动降级(自修复 → 规则引擎/兜底提示),演示永不中断
- **无 Key 离线模式**:Java 规则引擎覆盖牧场数据集;Python 离线规则支持电商星型 JOIN(从数据集描述解析关系),零依赖可演示

## 技术栈

| 层 | 技术 |
| --- | --- |
| 后端 | Java 21 · Spring Boot 3.5 · MyBatis-Plus · JSqlParser · Caffeine · jjwt · springdoc |
| Java LLM | **LangChain4j 0.36.2**(OpenAI 兼容协议) |
| Python Agent | **FastAPI · LangChain 0.3 · LangGraph 0.5**(ReAct + StateGraph)· langchain-mcp-adapters |
| 前端 | Vue 3 · TypeScript · Vite · Element Plus · Pinia · Vue Router · ECharts · Axios |
| 数据 | MySQL 8(平台库+数仓)+ PostgreSQL/pgvector(rag1 意图向量库) |
| 数据集 | ①电商零售·巴西 Olist(Kaggle 真实数据,默认展示)②智慧牧场合成 ③OWID/FAOSTAT 全球牛奶产量 |

## 快速开始

### 方式一:一键启停全栈(默认,推荐演示用)
```bash
./start-all.sh    # 仓库根执行:backend(含前端页面)+ MySQL + pgvector + python-agent,健康检查通过才返回
./stop-all.sh     # 一键全停;--purge 连数据卷一起清空(所有 Docker 定义集中在 docker/ 目录)
# 就绪后: http://localhost:8080 (admin/bovin123),再次启动仅需 ~10 秒(数据卷保留)
```

### 方式二:接入真实大模型(双引擎点亮)
```bash
export LLM_API_KEY=sk-xxx                        # DeepSeek / 通义 / GLM 任一 OpenAI 兼容 Key
export BOVIN_LLM_PROVIDER=openai                 # Java 侧(可选:BOVIN_CHAT_ENGINE 切 pipeline/agent/multi-agent/orchestra)
LLM_API_KEY=$LLM_API_KEY ./docker/start-all.sh   # Python 侧同 Key 自动点亮
```
LLM 任何一环失败自动进入降级链(自修复 → 规则兜底),演示永不中断。

### 方式三:本机开发(只起存储,后端跑在 IDE/Maven)
```bash
docker compose -f docker/docker-compose.yml up -d mysql pgvector   # MySQL@3308 + pgvector@5433
mvn spring-boot:run
```
> 测试同样跑真实 MySQL:`mvn test` 用 Testcontainers 起一次性容器(需要本机 Docker;
> colima 用户在 `~/.testcontainers.properties` 配 `docker.host=unix\:///$HOME/.colima/default/docker.sock`)。

### 前端开发模式
```bash
cd frontend && npm install && npm run dev    # http://localhost:5173,代理到 8080
npm run build                                # 产物输出到后端 static,单 JAR 部署
```

### Python Agent 单独运行
```bash
cd python-agent && pip install -r requirements.txt
JAVA_MCP_URL=http://localhost:8080/mcp uvicorn app.main:app --port 8090
curl http://localhost:8090/health    # framework: fastapi+langchain+langgraph + 底座工具清单
```

## 质量与基准

```bash
mvn test                            # 145 个单元/集成测试(管线降级链/安全/时间/规则引擎/图表/缓存/评测/隔离/审批/token)
mvn test -Dtest=Nl2SqlEvalRunner    # 评测集回归 → eval-report.md
```

## 目录结构

```
BovinBI/
├── src/main/java/com/eighthours/bovinbi/
│   ├── service/        # 管线:Nl2SqlService(编排+降级链)/SqlGuard/QueryExecutor/ChartAdvisor/
│   │                   #   SemanticCache/ChatService(引擎分流+审批流)/DataPortService(批量进出)…
│   ├── agent/          # 单 Agent 工具循环 + orchestra 多 Agent 编排(StateGraph 风格声明式工作流)
│   ├── mcp/            # MCP 工具统一注册中心 + 对外 /mcp 端点(JSON-RPC)
│   ├── rag1/           # 意图识别(pgvector 向量召回 + LLM 判别)
│   ├── trace/          # SSE 实时 AI 工作流(TraceHub)
│   ├── security/       # JWT/防爆破/审批中枢/UserContext(uid+role)
│   └── llm/ controller/ config/ entity/ mapper/ dto/ init/ common/
├── python-agent/       # 🐍 Python AI 能力层(FastAPI + LangChain + LangGraph,MCP 回环)
├── frontend/           # Vue3 + TS + Element Plus + ECharts(Text2SQL 工作台/实时步骤/审批确认卡)
├── src/main/resources/dataset/
│   ├── dwh/*.csv       # 合成牧场星型模型(演示数据)
│   └── real/           # OWID 牛奶产量 + Olist 电商星型三表(Kaggle 真实数据,默认展示)
├── rag1/intent-examples.csv  # 意图问例语料(加行即扩展)
├── sql/ docker/        # MySQL/pgvector 建表脚本 + compose + 一键启停
└── docs/               # 学习与面试文档
```

## 数据说明

默认展示 **「电商零售·巴西Olist」真实数据集**(Kaggle 公开,ETL 预处理为星型三表):
96,478 笔已送达订单 · 110,197 行订单明细 · 32,216 个商品 · 2016-09 ~ 2018-08,
真实世界事实可断言(销售额第一大州 SP 圣保罗 / 第一大类目 health_beauty / 峰值月 2017-11 黑五),
以真实数据测试锚定:数据装错/过滤错/翻译错测试即红。
另有合成牧场数据集(业务规律可审计)与 OWID/FAOSTAT 全球牛奶产量(1961 年起 191 国)可切换。
