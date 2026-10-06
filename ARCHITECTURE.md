# BovinBI 架构与数据流(每步标注代码路径)

> 全栈 4 容器:`backend`(Java,含前端页面) / `mysql`(业务+数仓) / `pgvector`(意图向量库) / `python-agent`(LangGraph AI 能力层)
> 一键启停:仓库根 `./start-all.sh` / `./stop-all.sh`;LLM Key:`docker/.env`(模板 `docker/env.example`)

---

## 一、提问主链路(核心数据流)

```
【前端】Chat.vue 输入问题(选 ☕Java / 🐍Python 引擎)
   │  send() → askStream()
   ▼
POST /api/chat/ask/stream ──────────────────────────────────────────────
   │  代码: frontend/src/views/Chat.vue → frontend/src/api/index.ts(askStream)
   ▼
【鉴权】JWT 解析 → UserContext(uid/username/role)
   │  代码: security/AuthInterceptor.preHandle → security/UserContext
   ▼
【SSE 流开启】TraceHub.open(queryId);异步执行(身份显式传递给子线程)
   │  代码: controller/ChatController.askStream → trace/TraceHub
   ▼
【会话校验】会话必须属于当前用户(越权 404)
   │  代码: service/ChatService.mustOwn
   ▼
【权限分流】用户 approval_mode(每用户独立存储)
   ├─ STEP(每一步过问)→ parse 出 SQL → SSE approval 事件 → 挂起等待
   │   POST /api/chat/approve/{queryId} 用户决策(120s 超时=取消) → execute
   │   代码: ChatService.runAskStepMode → security/ApprovalHub
   └─ AUTO(完全允许)→ 继续 ↓
   ▼
╔═══════════════════════════════════════════════════════════════════╗
║ 角色一 · 判别 AI:这是闲聊还是取数?                                  ║
║  LLM 判别优先(一个词判决) → pgvector 向量召回 → 关键词兜底           ║
║  代码: service/rag1/Rag1IntentService.recognize                      ║
║        → service/rag1/PgVectorStore.search(相似问例做少样本)          ║
╚═══════════════════════════════════════════════════════════════════╝
   ├─ 闲聊 ↓ ───────────────────────────────────────────────────────
   │  ╔═══════════════════════════════════════════════════════════╗
   │  ║ 角色二 · 闲聊 AI:真 LLM 对话(朋友人设,顺势引导查数据)          ║
   │  ║  engine=python → 转发 python-agent 闲聊节点                   ║
   │  ║  engine=java   → LlmClient.chat(SMALL_TALK_SYSTEM)           ║
   │  ║  离线/失败     → 固定文案兜底                                  ║
   │  ║  代码: ChatService.smallTalk / python-agent/app/agent.py        ║
   │  ║         chitchat_node(CHAT_SYSTEM)                            ║
   │  ╚═══════════════════════════════════════════════════════════╝
   │  → 落库(ChatMessage,载荷 engine=CHAT)→ SSE done → 前端对话卡片
   │     代码: ChatService.finishStep → mapper 落库 → TraceHub.finish
   │
   └─ 取数 ↓ ───────────────────────────────────────────────────────
      【引擎分流】ChatService.runAsk(engine 参数)
      ├─ engine=python ─────────────────────────────────────────┐
      │  HTTP → python-agent:8090/v1/answer                      │
      │  代码: service/PythonAgentService.answer                 │
      │  ▼ LangGraph StateGraph                                  │
      │  代码: python-agent/app/agent.py                          │
      │  intent_node(LLM 判别)→ 路由:                             │
      │    chitchat_node(LLM 对话)                                │
      │    agent_node(角色三:ReAct 工具循环)                       │
      │      create_react_agent(LLM + MCP 工具)                   │
      │      工具来自 langchain-mcp-adapters → Java /mcp          │
      │    fallback_node(离线提示)                                │
      │  → 返回 {sql, rows, steps} → Java 侧补图表                 │
      │    代码: ChatService.answerByPython(ChartAdvisor)          │
      │                                                           ▼
      └─ engine=java:service/Nl2SqlService.answer ──(与 Python 汇合)↓

╔═══════════════════════════════════════════════════════════════════╗
║ 角色三 · SQL Agent:自然语言 → SQL → 结果                              ║
║  Java 四引擎可选(BOVIN_CHAT_ENGINE):                                 ║
║   agent      AgentOrchestrator + BovinAgent 工具循环                  ║
║              代码: agent/AgentOrchestrator → agent/AgentConfig        ║
║                    → agent/BovinTools(@Tool)                          ║
║   multi-agent SQL→审查→修复流水线                                     ║
║              代码: service/impl/MultiAgentServiceImpl                 ║
║   orchestra  Supervisor 路由 + 声明式工作流                           ║
║              代码: agent/orchestra/WorkflowOrchestrator               ║
║   pipeline   LLM 生成+自修复+规则兜底                                 ║
║              代码: Nl2SqlService.generateValidateExecute              ║
║  公共前置(任何引擎前):                                               ║
║   时间解析 TimeRangeParser / 语义缓存 SemanticCache /                 ║
║   Schema 召回 SchemaRetriever(同义词+表白名单)                        ║
╚═══════════════════════════════════════════════════════════════════╝
   ▼
【工具统一入口】所有工具调用(两引擎共用)经 MCP 注册中心
   代码: mcp/McpToolRegistry.call(预算扣减/轨迹/异常隔离)
   → 工具实现 mcp/tools/*:getSchema / executeSql / getColumnValues / exportReport
   ▼
【SQL 守护】AST 级:仅单条 SELECT / 表白名单 / 强制 LIMIT
   代码: mcp/tools/ExecuteSqlTool → service/SqlGuard.validate
   ▼
【只读执行】独立通道:queryTimeout + maxRows
   代码: service/QueryExecutor.execute
   ▼
【图表推荐】按结果形态(时间→折线/类目→条形/份额→饼图/单值→大数字)
   代码: service/ChartAdvisor.advise
   ▼
【落库】助手消息 + 查询审计(query_log,普通用户只见自己)
   代码: ChatService(messageMapper/queryLogMapper)→ controller/HistoryController
   ▼
【SSE done】最终答案推给前端 → AnswerCard 渲染(表格/图表/SQL 折叠)
   代码: TraceHub.finish → frontend Chat.vue
```

---

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
