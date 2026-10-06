# BovinBI 简历项目经历(可直接改编)

> 用法:挑 4~6 条放进简历(别全放);每条都能被面试官追问 5 分钟。
> 写之前先把 ARCHITECTURE.md 吃透、把项目跑起来演示一遍。

---

## 简历上的项目块(推荐版本)

**BovinBI · 对话式商业智能(ChatBI)平台**(个人项目,独立开发)

一句话:自然语言 → SQL → 图表的全链路对话式 BI;Java 底座 + Python(LangGraph)AI 能力层的双 AI 引擎架构,提问时自由切换。

技术栈:Java 21 · Spring Boot 3.5 · MyBatis-Plus · LangChain4j / Python · FastAPI · LangChain · LangGraph / MySQL 8 · PostgreSQL · pgvector / Vue 3 · Docker

- **NL2SQL 全链路**:意图分流(LLM 判别优先+pgvector 向量召回) → 时间解析 → 语义缓存 → Schema 召回 → SQL 生成 → **AST 级安全守护**(仅单条 SELECT/表白名单/强制 LIMIT,jsqlparser 实现) → 只读执行 → 智能图表推荐;任何一环失败自动降级,对外永远"可用回答或优雅提示"
- **五种编排引擎并存**:pipeline(固定管线)/ agent(工具循环)/ multi-agent(SQL→审查→修复)/ orchestra(Supervisor 路由+声明式工作流)/ Python(LangGraph StateGraph:意图路由→ReAct);Python 引擎经 MCP 协议回环调用 Java 工具——**安全边界不因引入 Python 外流**
- **MCP 工具统一管理**:本地工具、外部 MCP 客户端、Python Agent 三种入口共用一个注册中心与执行通道(预算扣减/守护/轨迹一处实现);工具元数据(参数 schema+每工具配额+写操作标记)一处注册三处消费
- **多步计划执行器**(学自开源项目并差异化):LLM 输出结构化 JSON 计划 → 四道服务端校验(步数预算/工具存在性+写操作拦截/依赖补全/**手写 Kahn 拓扑排序做环检测**) → 按拓扑序执行,每步仍过 SQL 守护
- **生产化工程**:SSE 实时推送 AI 工作步骤;每用户独立的执行审批模式(完全允许/每一步过问,挂起-确认-放行);每用户每日 token 用量独立计量;用户数据隔离(会话/审计越权一律 404);145+ 单元/集成测试;Docker Compose 一键全栈
- **真实数据回归**:接入 Kaggle 电商真实数据集(11 万行订单)与 OWID 全球牛奶产量,以真实世界事实做测试断言(第一大州=SP/峰值月=2017-11 黑五),数据装错测试即红

*(6 条太多的话,优先保留:1、2、3、5)*

---

## 按岗位调整侧重

**Java 后端岗**(弱化 LLM 术语,强调架构与安全):
- 把标题里的"ChatBI"保留,但第一条改写为:"设计 SQL 安全守护体系:JSqlParser AST 校验(单语句/表白名单/强制 LIMIT),配合只读连接与超时控制,构成纵深防御;规则/LLM/Agent 多引擎降级链保证可用性"
- 突出:MyBatis-Plus/Spring 生态、SseEmitter、Testcontainers 真库测试、Docker 化、用户隔离与 JWT 鉴权(防爆破/时序均化)

**AI 应用开发岗**(现在的风口,全文案可直用):
- 突出:LangChain4j/LangChain/LangGraph 双语言栈、Agent 工具循环、ReAct、RAG(pgvector+HNSW)、意图判别、MCP 协议、多 Agent 编排、token 治理
- 面试常问"Agent 怎么防止失控":答预算在工具层强制(每工具配额+总调用上限),写操作进审批流

---

## 面试深挖准备(你亲身踩过的坑,就是最好的故事)

| 可能被问 | 你的故事(都在 git 历史里) |
|---|---|
| "遇到过最难的 bug?" | DeepSeek 域名双 A 记录,一个 IP 是黑洞:curl 正常、Java 必超时。最小复现锁定 OkHttp 无 Happy Eyeballs,库层无 DNS 注入口 → 应用层超时重试+JVM DNS 缓存 5s,第二次重解析即成功 |
| "缓存怎么设计的?" | key=数据集+归一化问题+时间区间,**刻意不含引擎/模型**(跨引擎共享省 token);能讲清"命中但 SQL 不同"的原因和取舍 |
| "多 Agent 怎么防幻觉?" | final_sql 必须命中"已成功执行"登记;时间对账把 LLM 抄错时间变成确定性拦截(还修过正则绑死列名的 bug);守护在执行层兜底 |
| "为什么 Python 和 Java 混用?" | Python 只做智力,执行与数据在 Java,MCP 回环;Python 挂了自动降级 Java 引擎,标签透明 |
| "LangChain 生态踩过什么坑?" | langgraph 0.6+/adapters 0.2 与 langchain 0.3 的 core 冲突、mcp SDK 2.x 移除 session 模块——版本矩阵钉死并写进 README |
| "审批模式怎么实现?" | CompletableFuture 挂起+SSE approval 事件,120s 超时视为取消;拒绝不消费解析暂存 |

---

## 写简历的三条纪律

1. **每一条都要能讲 5 分钟**——讲不出的一律删。上面每条背后你都有真实代码与排查记录(ARCHITECTURE.md 有全部路径)
2. **写决策不写功能**:"做了 X"不如"因为 Y 选择了 X,代价是 Z";你的差异化(预算治理+AST 护栏插进服务端校验层)就是别人没有的
3. **数字只写可验证的**:145+ 测试、11 万行真实数据、5 引擎、3 数据集——都真实存在;不要编"性能提升 300%"这类没测过的数

## 最后的诚实建议

这个项目的实现有 AI 辅助,但**每个决策为什么这么做、每个 bug 怎么定位的**,这轮对话里都有完整推演——把 ARCHITECTURE.md、这份文档、以及 3~4 个深挖故事真正内化,面试时它们就是你的。内化不了的条目,删掉。
