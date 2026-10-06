# BovinBI 架构与数据流(每步标注代码路径)

> 全栈 4 容器:`backend`(Java,含前端页面) / `mysql`(业务+数仓) / `pgvector`(意图向量库) / `python-agent`(LangGraph AI 能力层)
> 一键启停:仓库根 `./start-all.sh` / `./stop-all.sh`;LLM Key:`docker/.env`(模板 `docker/env.example`)

---

## 一、提问主链路

> 主链路图(含核心数据流与每步代码路径)为内部文档,不含在公开仓库中;
> 其余流程(SSE/装载/计划/计量/MCP/配置)如下,均已标注代码路径。

## 二、实时工作流(SSE,与主链路并行)

```
每个阶段/工具调用 → TraceHub.publish(queryId, name, detail, ok)
   代码埋点: ChatService / Nl2SqlService(意图/时间/缓存/Schema/引擎)
             AgentRunContext.record(Agent 每次工具调用)
             ChatService.answerByPython(Python 侧步骤回推)
   ▼
TraceHub:事件先入历史再推订阅者(前端建连竞态可回放)
   ▼
前端等待卡实时渲染步骤时间线(绿点/红点=成败)
   代码: trace/TraceHub → frontend Chat.vue(liveSteps)
```

## 三、启动装载流(./start-all.sh 时)

```
docker compose up → 4 容器
   ▼ backend 启动:
   ① 建表幂等          init/DataLoader.run → resources/schema-mysql.sql
   ② 装载三个数据集     DataLoader:牧场(合成)/ 全球牛奶(OWID)/ 电商(Olist Kaggle)
                        代码: DataLoader.loadDwhData/loadRealMilkData/loadEcommerceData
   ③ rag1 语料灌入      pgvector(空库才灌,76 条意图问例)
                        代码: service/rag1/Rag1Config → PgVectorStore.seedIfEmpty
   ④ MCP 工具目录       注册本地工具 + 远程 server(懒加载)
                        代码: mcp/McpToolRegistry
   ▼ python-agent 启动:连 backend /mcp,加载 5 个工具到 LangGraph
   代码: python-agent/app/main.py(/health 可验证链路)
```

## 四、多步计划链路(可选,复杂分析)

```
POST /api/chat/plan(只规划,可人工确认)
   代码: controller/ChatController.plan → mcp/plan/PlanExecutor.plan
   Planner(LLM 出结构化 JSON 计划,每步带依赖)
   → 四道校验:步数预算/工具存在性+写操作拦截/依赖补全/环检测(Kahn 拓扑排序)
     代码: mcp/plan/PlanOps.assemble(手写拓扑排序)
   → 校验反馈环:计划含 SQL 时先真实取 Schema 让 Planner 修订(消除编造表名)
     代码: PlanExecutor.plan(schema 反馈轮)
   ▼
POST /api/chat/plan/execute(确认后执行)
   按拓扑序逐步执行:每步仍过 McpToolRegistry(守护/配额同一入口)
   任一步失败即终止带原因;SSE 逐步推送
   代码: PlanExecutor.execute
```

## 五、Token 计量流(每用户每日)

```
LLM 调用(pipeline/multi-agent/orchestra 走 LangChain4jClient)
   代码: llm/LangChain4jClient.chat → recordUsage(响应 tokenUsage)
   ▼
service/TokenUsageService.record(uid, prompt, completion)
   → MySQL token_usage_daily 按用户+日期 upsert 累计
   ▼
查询:GET /api/usage/tokens(自己)/ ?all=true(ADMIN 全员)
   代码: controller/UsageController → 前端"查询历史"页卡片
```

## 六、MCP 工具三入口(同一份定义,同一道闸)

| 入口 | 路径 | 代码 |
|---|---|---|
| Agent 循环 | BovinTools @Tool → registry.call | agent/BovinTools |
| 外部 MCP 客户端 | POST /mcp(JSON-RPC,Bearer) | mcp/McpServerController |
| Python Agent | langchain-mcp-adapters → /mcp | python-agent/app/mcp_client.py |

工具元数据(参数 schema + 预算配额 + 写操作标记)一处注册三处共用:
`mcp/ToolSpec` + `McpToolRegistry.specs()`

## 七、关键配置速查

| 配置 | 位置 | 说明 |
|---|---|---|
| LLM Key | docker/.env(gitignore) | cp docker/env.example docker/.env 后填写;双引擎共用 |
| 引擎选择 | 前端切换 / BOVIN_CHAT_ENGINE | agent(默认)/multi-agent/orchestra/pipeline |
| 权限模式 | 顶栏下拉(每用户) | AUTO 完全允许 / STEP 每一步过问 |
| 端口 | compose | 8080(一切入口)/ 3308 MySQL / 5433 pgvector / 8090 python |
