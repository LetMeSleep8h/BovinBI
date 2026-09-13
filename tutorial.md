# BovinBI 源码课程(按调用链展开)

## 项目用途与本次学习目标

BovinBI 是一个面向业务人员的对话式商业智能(ChatBI)平台:用户用中文提问(如"近12个月每月产奶量趋势"),系统自动完成 **闲聊分流 → 时间解析 → 语义缓存 → Schema 召回 → SQL 生成(双引擎降级链)→ SQL 守护 → 只读执行 → 图表推荐** 六步链路并返回图表。业务域为智慧牧场·奶牛养殖(星型模型:`dwh_fact_milk` 事实表 + `dwh_dim_farm`/`dwh_dim_cattle` 维度表)。

本次学习目标:能够沿真实调用链讲清"一次提问从 HTTP 入口到图表返回"的完整过程,并理解两条查询引擎(固定 pipeline 与 Agent 工具循环)如何在同一外壳下共存、降级与兜底。

## 源码版本与影响结论的本地改动

- 当前工作区为 `main` 分支,最近提交 `e55b6e5`(精简查询链路重构)之后,存在**未提交改动**:`BovinProperties.java`、`AnswerPayload.java`、`ChatService.java`、`Nl2SqlService.java`、`application.yml`、`Nl2SqlEvalRunner.java`、`Nl2SqlServiceTest.java`,以及未跟踪的 `agent/` 包、`prompts/agent-system.md`、`src/test/.../agent/`。本课程按**工作区当前状态**(即包含 Agent 引擎的版本)展开。
- 关键配置现状:`bovin.chat.engine=pipeline`(默认固定管线),`bovin.llm.provider=mock`(离线规则引擎,无需 API Key)。

## 学习所需的前置基础

- Spring Boot 基础:Controller/Service 分层、`HandlerInterceptor`、`@ConfigurationProperties`、条件装配(`@ConditionalOnProperty`)、`ObjectProvider`。
- JDBC 与 SQL:`JdbcTemplate`、`Statement.setQueryTimeout/setMaxRows`、星型模型 JOIN。
- LLM 应用基础:Chat Completions、prompt 模板、tool-calling(function calling)概念、JSON 结构化输出。
- Java:record、switch 表达式、`ThreadLocal`、正则。

## 本次覆盖范围与尚未覆盖的模块

**覆盖**:后端查询主链(ChatController→ChatService→Nl2SqlService)、管线 0~6 步全部实现类、Agent 引擎全链(`agent/` 包 7 个类)、SQL 安全守护与只读执行、JWT 认证链、图表推荐、评测器入口。

**未覆盖**:前端 `frontend/`(Vue3/ECharts 渲染,本课程仅在需要时提及 `AnswerPayload` 的消费方)、`archive/legacy-backend-superstore`(已归档旧版,只作演进对比)、`DatasetService`/`DatasetController`(语义层数据的 CRUD,逻辑简单)、`HistoryController`(会话历史的常规查询)、`init/DataLoader`(演示数据装载)。这些模块可作为第 1 课后的选读。

---

## 课程大纲

| 编号 | 课程 | 读完能回答的问题 | 状态 |
| --- | --- | --- | --- |
| 01 | 端到端主链:一次提问的完整旅程 | 一次 `/api/chat/ask` 请求经过哪些组件,失败与成功分别怎么收尾? | 已展开 |
| 02 | 入口三件套:闲聊分流、时间解析、语义缓存 | 管线在真正"理解"问题之前先做了哪三件确定性的事? | 已展开 |
| 03 | Schema 召回与语义层 | 为什么不把全库 Schema 塞给 LLM?紧凑 Schema 文本和表白名单是怎么产出的? | 已展开 |
| 04 | Pipeline 引擎:LLM 生成与双引擎降级链 | LLM 生成的 SQL 失败后,系统如何自修复、如何降级、如何保证"永不中断"? | 已展开 |
| 05 | 规则引擎:离线兜底如何覆盖 9 类问题形态 | 没有 LLM 时系统怎么工作?规则引擎认不出问题时发生什么? | 已展开 |
| 06 | SQL 守护与只读执行:安全纵深 | 模型生成的 SQL 凭什么敢直接执行?有哪几道防线? | 已展开 |
| 07 | 图表推荐与回答组装 | 结果集如何决定折线/饼图/条形?最终载荷里有什么? | 已展开 |
| 08 | Agent 引擎:工具循环与预算约束 | Agent 模式和 pipeline 的本质区别?模型自主性如何被"工具层预算"约束? | 已展开 |
| 09 | Agent 的结果回填与信任但校验 | 模型最终返回的 SQL 如何取回结果?对不上登记怎么办? | 已展开 |
| 10 | 认证与数据归属:JWT 拦截链 | 请求进来先过谁?用户为什么只能看自己的会话? | 已展开 |
| 11 | 评测回归:怎么证明改链路没有变差 | 一条命令如何对比 pipeline 与 agent 的准确率? | 已展开 |

建议阅读顺序即编号顺序;01 是主干,02~07 是 pipeline 逐步深入,08~09 是 Agent 引擎,10~11 是支撑面。未覆盖模块在前 3 课后按需选读。

---

## 第 01 课 端到端主链:一次提问的完整旅程 【已展开】

**场景与目标**:用户在前端输入"近12个月每月产奶量趋势"并点发送。读完本课,你应能从 HTTP 入口一路指到落库返回,说清成功与失败两种收尾。

**主链**:`POST /api/chat/ask` → `ChatController.ask` → `ChatService.ask`(持久化用户消息)→ `Nl2SqlService.answer`(六步管线,详见 02~07 课)→ 组装 `AnswerPayload` → 持久化助手消息 + 写 `query_log` 审计 → 返回 `MessageVO`。

**必读顺序**:

1. `src/main/java/com/eighthours/bovinbi/controller/ChatController.java` — `ask()`(45 行)。输入:`ChatReq`(sessionId + question,`@Valid` 校验);下一步委托 `ChatService.ask`;输出包成统一响应 `ApiResponse.ok(...)`。
2. `src/main/java/com/eighthours/bovinbi/service/ChatService.java` — `ask()`(第 76~139 行)。这是本课核心:
   - 首问设置会话标题(截前 20 字,第 78~83 行);
   - **先落库用户消息**(role=USER,第 85~89 行)——先持久化再查询,保证失败时提问也不丢;
   - 计时 `t0` 开始,构造 `QueryLog` 骨架;
   - 调 `nl2SqlService.answer(datasetId, question, sessionId)`(第 101 行)——注意 sessionId 参数:agent 模式用它做多轮记忆锚点,pipeline 模式忽略;
   - **失败分支**(第 104~110 行):任何异常都被捕获,转成 `fallback=true` 的载荷,engine 记为 `"FAILED"` ——异常不会逃出本方法,接口永远 200;
   - 助手消息 content 取 `explanation` 或默认"已完成查询,共 N 行结果",payload JSON 序列化后随消息落库(第 118~127 行);
   - 审计:query_log 记录 finalSql/engine(含 CACHE)/status/rowCount/costMs/cacheHit/errorMsg(第 129~136 行)。
3. `src/main/java/com/eighthours/bovinbi/dto/AnswerPayload.java` — 全链路的"回答合同":sql/explanation/columns/rows/chart/engine/trace/fallback/cacheHit。
4. `src/main/java/com/eighthours/bovinbi/service/Nl2SqlService.java` — 只看类注释(第 14~28 行)和 `answer()` 方法签名,内部六步是 02~07 课的内容,本课暂缓。

**重要分支**:
- `mustOwn()`(ChatService 第 146~152 行):会话不存在或不属于当前用户 → 抛 404 `BizException`,由 `common/GlobalExceptionHandler` 统一转响应。这是"数据归属"在问答侧的执行点,认证链见第 10 课。
- `payload.isFallback()` 两种来源:管线判定"无法理解"(engine=RULE 等)与执行异常(engine=FAILED),前端表现一致(展示 fallbackHint)。

**完整流程串联**:以"产奶量Top5牧场"为例——请求带 JWT 进来(第 10 课),拦截器把 uid 放进 ThreadLocal;`ask` 校验会话归属 → 存用户消息 → 进管线得到带 chart 的 `AnswerPayload` → 存助手消息(payload 含 SQL 与图表规格)→ 写 query_log(costMs/cacheHit)→ 前端拿 `MessageVO.payload` 渲染表格与图表。失败变体:若管线抛异常,query_log 的 status=FAILED、errorMsg 截断到 900 字,助手消息显示兜底提示。

**选读证据**:`common/GlobalExceptionHandler.java`(BizException → 错误码映射);`mapper/QueryLogMapper.java`(审计表 MyBatis-Plus 映射)。

**暂缓阅读**:`Nl2SqlService` 内部各步 → 第 02~07 课;`AgentOrchestrator` → 第 08 课。

---

## 第 02 课 入口三件套:闲聊分流、时间解析、语义缓存 【已展开】

**场景与目标**:管线第 0~2 步。这三步全是**确定性代码**,共同目标是"概率性的事交给模型,确定性的交给代码"。读完应能解释:为什么闲聊要在最前面拦、时间为什么不用 LLM 解析、缓存 key 怎么设计。

**主链**:`Nl2SqlService.answer` 第 55~67 行:① `chitChatHandler.isChitChat` 命中 → 直接 `answer()` 返回(不进管线);② `timeRangeParser.parse` → `TimeRange` 或 null;③ `semanticCache.key/get` 命中 → `cacheHit()` 拷贝返回。

**必读顺序**:

1. `src/main/java/com/eighthours/bovinbi/service/Nl2SqlService.java` 第 54~67 行 — 三步的编排位置与注释。
2. `src/main/java/com/eighthours/bovinbi/service/ChitChatHandler.java` — 两个正则:
   - `META`:问候/能力询问/道谢特征词;
   - `DATA_SIGNAL`:数据信号词(奶量/趋势/top/占比/…)。判闲聊的条件是**短句(≤50 字)且命中 META 且不命中 DATA_SIGNAL**——"你好,帮我看看上月奶量"不会被误伤。
3. `src/main/java/com/eighthours/bovinbi/service/TimeRangeParser.java` — 规则式中文时间解析:近 N 天/近 N 月/上季度/今年Q1/上个月/2025年3月/"3月"就近原则(9 月问"3月"理解为今年 3 月)。所有区间都是**半开区间 `[start, end)`**;构造器可注入固定 `LocalDate` 保证评测可复现(第 27~33 行)。
4. `src/main/java/com/eighthours/bovinbi/service/TimeRange.java` — record 三字段 + `toSqlCondition()`(生成 `>= start AND < end`,利于索引)+ `cacheKey()`。
5. `src/main/java/com/eighthours/bovinbi/service/SemanticCache.java` — Caffeine 缓存,TTL 600s/上限 512(来自 `BovinProperties.Cache`);key = `datasetId|归一化问题|时间区间`。`normalize()` 折叠大小写/空白/标点("总销售额?"与"总 销售额!"同 key);`put()` **只缓存非 fallback 且有 SQL 的成功结果**。

**重要分支**:
- 缓存命中(`Nl2SqlService.cacheHit`,第 170~183 行):显式新建 `AnswerPayload` 逐字段拷贝并打 `engine=CACHE`——**不返回缓存对象本身**,防止调用方改动污染缓存。
- 时间解析返回 null = 无时间语义,由后续 SQL 生成决定"默认不加时间过滤"。

**完整流程串联**:输入"你好" → isChitChat=true → 立即返回能力介绍,耗时 1ms,engine=RULE;输入"上个月各品种产奶量占比"(缓存已有)→ 时间解析 [2026-08-01, 2026-09-01) → 归一化 key 命中 → 拷贝返回 engine=CACHE。两者都跳过了 LLM 与 SQL 执行。

**选读证据**:`src/test/java/com/eighthours/bovinbi/evaluation/TimeRangeParserTest.java`、`SemanticCacheTest.java`(行为已被测试固定)。

**暂缓阅读**:第 3 步之后怎么用 TimeRange → 第 04/05 课。

---

## 第 03 课 Schema 召回与语义层 【已展开】

**场景与目标**:管线第 3 步。读完应能回答:为什么不把全库 Schema 给 LLM?紧凑 Schema 文本、表白名单分别喂给谁?

**主链**:`schemaLinker.link(datasetId, question)` → 查 `Dataset`(表白名单)+ `DatasetField`(字段语义)→ 按问题关键词打分排序 → 拼紧凑 Schema 文本 → 返回 `LinkedSchema(schemaText, whitelist)` → 装进 `SqlGenContext` 贯穿后续三步。

**必读顺序**:

1. `src/main/java/com/eighthours/bovinbi/service/SchemaLinker.java` 全文(93 行):
   - 白名单来源:`Dataset.dwhTables` 字段(逗号分隔),小写化后入 Set(第 39~40 行);
   - 打分规则:问题包含字段 `alias` +3、包含 `columnName` +2、包含任一 `synonyms` +2(第 51~59 行);
   - **排序的意义**:命中问题的字段排前面,"LLM 的注意力资源留给最相关的列";
   - 文本格式:【数据集】→【可查询物理表】→ 按表分组列字段,指标带聚合方式(如 `SUM`),维度标"(维度)",同义词与口径描述一并进入。
2. `src/main/java/com/eighthours/bovinbi/service/SqlGenContext.java` — record:`question/timeRange/schemaText/whitelist`,一次召回、三步复用。
3. `src/main/java/com/eighthours/bovinbi/entity/Dataset.java` 与 `DatasetField.java` — 语义层存储:alias(业务名)/fieldType(METRIC/DIMENSION)/aggType/synonyms/description(口径)。这就是"语义层":业务黑话(奶量/头数)通过同义词与别名映射到物理列。
4. `src/main/resources/prompts/nl2sql-system.md` 的 #Rules 第 3 条 — "表和列必须来自召回的 Schema" 是提示词层的软约束,硬约束在守护层(第 06 课)。

**重要分支**:
- 数据集不存在或 `dwhTables` 为空 → 抛 `BizException`(第 42~43 行),上层按"问答失败"收尾。
- Agent 模式复用同一个 `SchemaLinker`(`BovinTools.getSchema` 直接调用并登记白名单),说明召回能力被两条引擎共享。

**完整流程串联**:输入"上个月各品种产奶量占比"——"品种"命中 `c.breed` 的 alias(+3),没有命中"牧场规模"等无关字段,于是 Schema 文本里 `dwh_dim_cattle.breed` 排在最前;同时白名单 `{dwh_fact_milk, dwh_dim_cattle, dwh_dim_farm}` 交给 `SqlGenContext`,后续无论谁生成的 SQL,守护都按这份白名单校验。

**选读证据**:`DatasetService.java`(字段维护接口,语义层即时生效的原因);`DatasetController.java`。

**暂缓阅读**:schemaText 如何进 prompt → 第 04 课;如何进 Agent 工具 → 第 08 课。

---

## 第 04 课 Pipeline 引擎:LLM 生成与双引擎降级链 【已展开】

**场景与目标**:管线第 4~5 步的 LLM 路径,也是全项目最核心的设计。读完应能画出完整的降级链,并解释"自修复一次"发生在哪、为什么这么设计。

**主链**:`generateValidateExecute(ctx)`(Nl2SqlService 第 123~152 行):
`LLM 生成 → [生成失败]→ 规则引擎`;`LLM 生成 → 守护/执行 [失败]→ LLM 自修复一次 → [仍失败]→ 规则引擎`。

**必读顺序**:

1. `Nl2SqlService.java` 第 123~152 行 `generateValidateExecute` — 逐行读:
   - `llmMode` 判断:provider=openai 才走 LLM,mock 时规则引擎即主引擎(第 124~127 行);
   - 首次尝试:`llmSqlGenerator.generate(ctx)` → `validateAndExecute`(第 130~133 行);
   - catch 后先看 `fallbackToRule` 开关(第 135~138 行):关闭则直接把异常抛给上层(适合想看到真实错误的场景);
   - **只有已拿到 SQL(即守护/执行阶段失败)才值得自修复**(第 140~149 行)——生成阶段都没成功,没有可修的对象;修复仍失败落规则引擎,engine 标 `"RULE(降级)"`。
2. `src/main/java/com/eighthours/bovinbi/service/LlmSqlGenerator.java` 全文(100 行):
   - 系统模板来自 `prompts/nl2sql-system.md`(#Role/#Task/#Rules/#Exemplars 四段),用户模板 `#Schema/#SideInfo/#Question` 三变量,用 LangChain4j `PromptTemplate` 填充;
   - `repair()` 是独立入口:把失败 SQL 与数据库报错拼进用户提示(第 79~91 行),约束"聚合条件放 HAVING、只用 Schema 表列";
   - 本类不自带重试——重试策略全部在 Nl2SqlService 的降级链里,职责分离。
3. `src/main/resources/prompts/nl2sql-system.md` 全文 — 注意:口径规则(产奶量=SUM、单产=AVG、泌乳牛数=COUNT DISTINCT)写在规则第 5 条;SideInfo 规则第 6 条说明时间由代码解析,LLM 只需照抄区间;3 个 few-shot 覆盖趋势/占比/TopN。
4. `src/main/java/com/eighthours/bovinbi/llm/LangChain4jClient.java` — 项目唯一 LLM 出口:
   - `chat()`:SystemMessage + UserMessage → `OpenAiChatModel.generate`;
   - **懒构建 + 双检锁**(第 44~58 行):provider=mock 时永不创建实例,零外部依赖启动;
   - `extractJson()`:先直接 parse,失败用正则 `\{.*\}` 抓取——容忍 markdown 代码块包裹。
5. `src/main/java/com/eighthours/bovinbi/llm/LlmClient.java` — 接口只有 `chat` + `extractJson`,测试用手写替身(`LlmSqlGeneratorTest`)。

**重要分支**:
- `validateAndExecute`(Nl2SqlService 第 164~167 行)= `sqlGuard.validate` + `queryExecutor.execute`,两者任一抛异常都算"执行阶段失败"→ 进自修复分支。
- 自修复成功 engine=`"LLM(修复)"`,对外透明可观测。

**完整流程串联**:provider=openai,输入"近3个月各牧场平均乳脂率"——Schema 召回文本进 #Schema;SideInfo 给出 `[2026-06-13, 2026-09-14)`;LLM 返回 JSON `{"sql": "...", "explanation": "..."}` → SqlGuard 通过 → 执行报错"Unknown column 'fat_rate' in having clause" → `repair()` 带上报错重写 → 再走守护+执行成功 → engine=LLM(修复)。若修复也失败 → 规则引擎接手,engine=RULE(降级)。对外只有"可用回答"或"优雅降级"两种结局。

**选读证据**:`src/test/java/com/eighthours/bovinbi/service/LlmSqlGeneratorTest.java`、`Nl2SqlServiceTest.java`(降级链行为测试);`eval-runs/AB-SUMMARY.md`(两引擎评测对比)。

**暂缓阅读**:守护与执行细节 → 第 06 课;Agent 版本的同一职责 → 第 08 课。

---

## 第 05 课 规则引擎:离线兜底如何覆盖 9 类问题形态 【已展开】

**场景与目标**:provider=mock 时它是主引擎(演示零依赖),openai 时它是最终兜底。读完应能说明它的分支顺序、认不出问题时的行为,以及它与 LLM 引擎的输入差异。

**主链**:`Nl2SqlService.byRule(ctx, engine)`(第 155~161 行)→ `RuleSqlGenerator.generate(ctx)` → 返回 null = "无法理解" → `Answer(null,...)` → 上层 `fallbackPayload()`(第 185~192 行)输出兜底提示;返回 SqlResult → 照常守护+执行。

**必读顺序**:

1. `src/main/java/com/eighthours/bovinbi/service/RuleSqlGenerator.java` `generate()`(第 25~96 行),分支**顺序敏感**:环比 → 同比 → TopN → 占比/分布 → 时间趋势(含时间×维度)→ 维度汇总 → 带维度值过滤的总指标 → 总体指标。每个分支用 text block 拼 SQL,固定三表 JOIN(`FROM` 常量,第 19~23 行)。
2. `metric(q)`(第 216~238 行)— 指标识别,**长词优先**(先"乳蛋白率"再"蛋白率",防子串抢先);无指标关键词返回 null。
3. `dim(q)` / `valueFilter(q)`(第 240~282 行)— 维度识别(牧场/规模/地区/品种/泌乳阶段/牛舍/胎次/周末)与维度值过滤(地区/规模/品种/阶段/周末硬编码 WHERE)。
4. `topN(q)`(第 284~294 行)— 三种写法抽 N:`top 5`/`前5`/`5名`,默认 10。
5. 类注释(第 9~16 行)——**关键差异**:规则引擎按本数据集口径硬编码,**不依赖 Schema 召回结果**(那是 LLM 引擎的输入);SqlGenContext 里它只用 question 与 timeRange。

**重要分支**:
- 最后的总体指标分支(第 88~96 行):`metric(q)==null` 返回 null → 上层优雅降级。这是规则引擎"承认自己不懂"的唯一出口,保证兜底不会瞎编 SQL。
- 每个成功分支返回 `SqlResult(sql, explanation)`,explanation 直接成为助手消息内容。

**完整流程串联**:mock 模式输入"上个月产奶量环比"——命中环比分支,tr 为 [2026-08-01, 2026-09-01),prev 为 7 月,UNION ALL 两段对比 SQL → 守护执行 → 返回两行结果,explanation="对比 2026年8月 与 2026年7月 的产奶量"。输入"公司今年营收多少"——所有分支不命中、metric=null → null → 前端显示"我暂时理解不了这个问题…"(fallbackHint 给出示例问题引导)。

**选读证据**:`src/test/java/com/eighthours/bovinbi/evaluation/RuleSqlGeneratorTest.java`、`eval-report.md`(123 条评测集结构准确率 100% 的证据)。

**暂缓阅读**:同问题在 Agent 模式下如何被处理 → 第 08 课。

---

## 第 06 课 SQL 守护与只读执行:安全纵深 【已展开】

**场景与目标**:无论 SQL 来自 LLM、自修复还是 Agent 的 `executeSql`,执行前都必须过同一道守护。读完应能列出全部防线,并解释为什么"提示词是建议,工具是法律"。

**主链**:`SqlGuard.validate(sql, whitelist)` → AST 校验/表白名单/强制 LIMIT/去注释分号 → `QueryExecutor.execute(sql)` → 只读通道 + 超时 + maxRows → `ExecResult`。

**必读顺序**:

1. `src/main/java/com/eighthours/bovinbi/service/SqlGuard.java` 全文(93 行),按执行顺序:
   - 去尾分号、去 `/* */` 与 `--` 注释;含 `;` → 拒绝(多条语句);
   - `CCJSqlParserUtil.parse` 语法解析,失败即拒;`instanceof Select` 校验(仅 SELECT);
   - **AST 级表白名单**:`TablesNamesFinder` 从语法树收集所有表,逐个(剥引号/库名前缀,小写)比对白名单,未授权表 → 403(第 62~77 行);
   - 强制 LIMIT:PlainSelect 直接 `ps.setLimit`;UNION 等集合操作用正则检查后字符串追加(第 79~92 行)。上限来自 `bovin.chat.max-rows=1000`。
2. `src/main/java/com/eighthours/bovinbi/service/QueryExecutor.java` 全文(87 行):
   - `Statement.setQueryTimeout(8s)` + `setMaxRows(1000)` 双保险(第 35~37 行);
   - JDBC 类型归一化(LocalDateTime/LocalDate/byte[]/BigDecimal → 前端友好格式,第 73~82 行);
   - 失败取 `getMostSpecificCause` 的消息抛 `BizException`——这个消息正是第 04 课自修复和第 08 课"错误即数据"的输入。
3. `src/main/java/com/eighthours/bovinbi/config/DwhDataSourceConfig.java` — 分析通道与平台库分离:`bovin.dwh.url` 留空复用主数据源(演示),配置后建**独立只读 HikariCP 连接池**(`setReadOnly(true)`,池 8)。这是第 5 道防线:连接本身只读。

**重要分支**:
- 守护拒绝 vs 执行失败是两类错误:前者是安全违规(提示"按硬约束改写"),后者是 SQL 错误(提示具体修法)。在 Agent 工具里(`BovinTools.executeSql`)两者返回话术不同,但都作为**数据**回喂模型。
- `PlainSelect` 与集合操作的 LIMIT 处理不同(代码第 79~92 行的两个 if 分支)。

**完整流程串联**:假设模型生成 `DROP TABLE dwh_fact_milk;`——含分号被拒;改成 `DELETE FROM dwh_fact_milk`——非 Select 被拒;改成 `SELECT * FROM mysql.user`——表白名单 403;改成合法 SELECT 但忘写 LIMIT——自动追加 LIMIT 1000;执行时若全表扫超 8 秒——queryTimeout 掐断。五道防线:AST 单语句、表白名单、强制 LIMIT、只读连接、超时+maxRows。

**选读证据**:`src/test/java/com/eighthours/bovinbi/evaluation/SqlGuardTest.java`(8 个安全单测);`application.yml` 的 `bovin.chat` 段。

**暂缓阅读**:无——本课是终点站,前后都已被前课覆盖。

---

## 第 07 课 图表推荐与回答组装 【已展开】

**场景与目标**:管线第 6 步。读完应能解释图表类型如何从"结果形态 + 问题意图"两路信号推出,以及长表如何透视成多序列。

**主链**:`chartAdvisor.advise(question, columns, rows)` → `ChartSpec(type, xName, x, series, reason)` → 塞进 `AnswerPayload.chart` → 前端 ECharts 直接消费。

**必读顺序**:

1. `src/main/java/com/eighthours/bovinbi/service/ChartAdvisor.java` `advise()`(第 24~90 行),决策顺序:
   - 先对每列分类:数值列(`isNumericType` 看 JDBC 类型名,`isNumericValues` 兜底看前 10 行实际值)/ 时间列(`looksLikeTime`:列名正则 + 前 5 行日期样值)/ 类别列;
   - 单行单指标 → "none"(指标卡);
   - 时间列+类别列+数值列且 ≥3 列 → `pivot()` 透视;
   - 仅时间+数值 → `line`(最多取 3 个数值列做多序列);
   - 类别+数值 → 问题命中占比词 且类别 2~8 且全正 → `pie`;否则类别 >12 或标签平均长度 >6 → 横向 `barH`,否则 `bar`。
2. `pivot()`(第 92~117 行)— 长表(时间,类别,数值)→ 宽表:TreeSet 排序 x 轴,LinkedHashMap 保序类别序列,最多 8 条序列,缺位补 0。
3. `src/main/java/com/eighthours/bovinbi/dto/ChartSpec.java` — 前后端图表合同。
4. 回看 `Nl2SqlService.answer` 第 95~108 行 — 成功路径的组装顺序:sql/explanation/columns/rows/chart/tookMs/engine,最后 `semanticCache.put` 写缓存(fallback 不写,呼应第 02 课)。

**重要分支**:
- `advise` 对空结果返回 `type=none, reason="无数据可绘制"`——图表推荐永不抛异常中断主链。
- Agent 路径同样调用 `chartAdvisor`(Nl2SqlService 第 78 行),两引擎产出同构。

**完整流程串联**:"近12个月每月产奶量趋势"返回 (月份, 产奶量) 两列——月份列被 `looksLikeTime` 命中(列名含"月份")→ line;"上个月各品种产奶量占比"返回 (品种, 产奶量)——问题含"占比"、品种数 ≤8、全正 → pie;"产奶量Top10牧场"——10 个长牧场名 → barH。reason 字段把选择依据带给前端展示。

**选读证据**:`src/test/java/com/eighthours/bovinbi/evaluation/ChartAdvisorTest.java`;`frontend/src/`(ECharts 渲染端,选读)。

**暂缓阅读**:无。

---

## 第 08 课 Agent 引擎:工具循环与预算约束 【已展开】

**场景与目标**:`bovin.chat.engine=agent` 时,管线第 3~5 步(召回/生成/守护/执行/修复)的**编排权从固定代码转移给模型**,外壳(闲聊/缓存/图表)不变。读完应能讲清工具循环的驱动方式、三个工具的职责,以及"自主性的边界在工具层强制"的含义。

**主链**:`Nl2SqlService.answer` 第 70~83 行(engine=agent 分支)→ `AgentOrchestrator.answer` → 建 `AgentRunContext` → `runLoop`(ThreadLocal set → `agent.chat(memoryId, userMessage)` → finally clear)→ `assemble` → 回到外壳补图表+写缓存。

**必读顺序**:

1. `src/main/java/com/eighthours/bovinbi/agent/AgentOrchestrator.java` 全文(155 行):
   - `answer()` 第 37~56 行:建上下文;`agentProvider.getIfAvailable()` 为 null(provider≠openai,BovinAgent 未装配)→ 直接规则兜底;任何异常 → `ruleFallback`。
   - `runLoop()` 第 64~74 行:SideInfo(今天日期+时间区间)拼进用户消息;`sessionId==null` 时用 `System.nanoTime()` 作一次性 memoryId,防评测题目间记忆串扰;**try-finally 清理 ThreadLocal 是硬约束**。
2. `src/main/java/com/eighthours/bovinbi/agent/AgentConfig.java` — 装配:`@ConditionalOnProperty(provider=openai)` 创建 BovinAgent;`AiServices.builder` 把 接口+模型+系统提示词+记忆(滑动窗口 20 条,memoryId=会话 id)+工具组建成动态代理;**工具循环由框架驱动**,应用侧只声明不手写。
3. `src/main/java/com/eighthours/bovinbi/agent/BovinAgent.java` — 23 行接口,`@MemoryId long` + `@UserMessage String`,约定最终回答为 JSON。
4. `src/main/java/com/eighthours/bovinbi/agent/BovinTools.java` 全文(174 行)——三个工具:
   - `getSchema(keywords)`:复用 SchemaLinker,并把白名单**登记进运行上下文**(第 40~41 行);提示词要求"写任何 SQL 之前必须先调用";
   - `getColumnValues(table, column, keyword)`:查维度真实可选值防编造;表/列名必须匹配 `IDENTIFIER` 正则(第 18 行,杜绝标识符注入);keyword 去 `'` 防 LIKE 注入;结果最多 20 个;
   - `executeSql(sql)`:唯一执行入口,内置守护+执行;成功把结果登记进上下文(`ctx.markResult`),失败返回**根因消息 + 修法提示**(第 130~140 行)。
   - 三个工具第一步都是 `ctx.tryConsume(tool)`:超预算返回"拒绝话术"而非抛异常——**错误即数据**,让模型自己收尾。
5. `src/main/java/com/eighthours/bovinbi/agent/AgentRunContext.java` — 请求级状态三重职责:
   - **预算账本**:`tryConsume`(第 82~99 行)总调用/SQL/Schema/维度值各自限次(配置默认 12/3/2/4);
   - **结果登记**:`markResult` 双键登记(原始 SQL 与守护重排版都归一化入 map,第 116~121 行),`findResult` 容忍大小写空白差异;
   - **轨迹收集**:trace 上限 64 条,但真实上界是预算。
6. `src/main/java/com/eighthours/bovinbi/agent/AgentContextHolder.java` — ThreadLocal 传递请求上下文的原因:LangChain4j 0.36 的 @Tool 方法没有请求级参数(1.x 才有 ToolContext)。
7. `src/main/resources/prompts/agent-system.md` — #Tools/#Workflow/#SQL 硬约束/#最终回答格式;关键句:"final_sql 必须是 executeSql 成功执行的那条 SQL 原文,一字不改"、"执行成功后即可收尾,禁止空耗预算"。

**重要分支**:
- 预算触顶:模型收到的是文本拒绝,可以继续对话(如输出最终 JSON),但不占资源——框架循环因此天然有界,模型"反复重试"也不会失控烧钱。
- `requireContext()`(BovinTools 第 152~158 行):上下文缺失直接抛 IllegalStateException——工具只能由 orchestrator 发起的循环调用,属于编程错误要暴露而非吞掉。

**完整流程串联**:engine=agent,输入"荷斯坦牛近3个月的平均单产,再按牧场拆一下"——模型先 `getSchema("单产 牧场")`(白名单入上下文)→ `getColumnValues("dwh_dim_cattle","breed","荷斯坦")` 确认值写法 → `executeSql` 第一次因把聚合条件写进 WHERE 被数据库拒绝 → 读报错改 HAVING 重试成功 → 输出 `{"final_sql":"...","explanation":"..."}`。整个过程 trace 记录每次调用,随 payload 返回前端展示"思考过程"。第二轮追问"那按品种呢"——同 memoryId 共享记忆窗口,SideInfo 重新解析本轮时间。

**选读证据**:`src/test/java/com/eighthours/bovinbi/agent/BovinToolsTest.java`(预算/白名单/登记行为测试)、`AgentRunContextTest.java`;`docs/06-Agent化改造学习报告.md`(设计动机)。

**暂缓阅读**:`assemble` 的回填细节 → 第 09 课。

---

## 第 09 课 Agent 的结果回填与信任但校验 【已展开】

**场景与目标**:工具循环结束后,模型说"final_sql 是这条"。凭什么信?读完应能讲清"登记回填"与"重新守护执行"两条路,以及为什么绝不盲信模型输出。

**主链**:`AgentOrchestrator.assemble(ctx, raw, t0)`(第 83~115 行):`llmClient.extractJson(raw)` → 取 `final_sql`/`explanation` → `ctx.findResult(finalSql)` 命中 → 直接取回已执行结果;未命中 → 重新 `sqlGuard.validate` + `queryExecutor.execute`。

**必读顺序**:

1. `AgentOrchestrator.assemble` 全文——三个分支:
   - `final_sql` 为空 → 抛 BizException → 上层 `ruleFallback`(第 60~63 行);
   - **命中登记**(第 95~99 行):零成本取回,`guardedSql` 用登记里的守护版(不暴露模型原始写法);
   - **未命中**(第 100~106 行):模型改写了 SQL 或幻觉——"信任但校验",白名单为空时现场再 link 一次,重新守护+执行,失败整体降级。SQL 从不跳过守护直接执行。
2. 回看 `AgentRunContext.markResult/findResult/normalizeSql`(第 116~130 行)——双键 + 归一化是"命中"能成立的前提。
3. `AgentOrchestrator.ruleFallback`(第 118~152 行)——Agent 链路的最终防线:先填 fallback 骨架,再尝试规则引擎(时间解析→Schema 召回→规则生成→守护→执行),成功则翻转为正常回答;规则也失败就保持优雅降级。engine 标记区分 `"AGENT(未配置LLM→RULE)"` 与 `"AGENT(降级RULE)"`。

**重要分支**:
- 异常路径与"质量差"路径殊途同归:`runLoop` 抛异常、final_sql 缺失、回填校验失败,全部落到 `ruleFallback`。
- 对外合同不变:无论哪条引擎,前端拿到的都是同一个 `AnswerPayload` 结构。

**完整流程串联**:模型最终回答把 SQL 里的换行改了格式——`normalizeSql` 折叠空白后仍命中登记,零成本返回;模型若幻觉出一条从未执行过的 SQL——findResult 为 null → 重新走守护(若引用未授权表直接 403)→ 执行失败 → ruleFallback 用规则引擎救一次 → 仍不行则给兜底提示。任何情况下"能执行出去的 SQL 一定过了守护"这一不变量成立。

**选读证据**:`docs/07-Agent面试故事集.md`(这段设计的叙事版);`eval-runs/agent-run1.md`(Agent 引擎评测原始记录)。

**暂缓阅读**:无。

---

## 第 10 课 认证与数据归属:JWT 拦截链 【已展开】

**场景与目标**:所有业务接口(除登录)都要先过认证;用户只能访问自己的会话。读完应能讲清令牌从签发到 ThreadLocal 的完整链路。

**主链**:`POST /api/auth/login` → `AuthService.login`(验密码→`JwtUtil.issue`)→ 前端携带 `Authorization: Bearer <token>` → `AuthInterceptor.preHandle` → `UserContext.set(uid, username)` → 业务代码 `UserContext.uid()` → `afterCompletion` 清理。

**必读顺序**:

1. `src/main/java/com/eighthours/bovinbi/security/JwtUtil.java`(43 行)— HS256 对称签名,secret 与 TTL 来自 `BovinProperties.Security`(默认 24h);`verify` 异常一律返回 null(令牌无效与过期的对外表现一致,不泄露细节)。
2. `src/main/java/com/eighthours/bovinbi/security/AuthInterceptor.java`(38 行)— `HandlerInterceptor.preHandle`:OPTIONS 预检放行;无 Bearer 头 → 401;验证通过把 uid/username 写入 `UserContext`。**afterCompletion 里 `UserContext.clear()`**——与第 08 课 `AgentContextHolder` 同一套 ThreadLocal 纪律:用完必清,防线程池复用串号。
3. `src/main/java/com/eighthours/bovinbi/config/WebConfig.java` — 拦截器注册与放行路径(登录/文档)。
4. `src/main/java/com/eighthours/bovinbi/service/ChatService.java` 的 `mustOwn()`(第 146~152 行)— 数据归属:会话存在但 userId 不等于当前 uid → 同样报 404(不区分"不存在"与"无权",避免探测)。

**重要分支**:
- 认证失败(401)与数据归属失败(404)是两层:前者在拦截器,后者在各 Service。
- `UserContext.uid()` 在 `ChatService.ask` 里被两处消费:会话归属校验 + query_log 审计字段。

**完整流程串联**:用户 A 拿自己的 token 请求删除用户 B 的会话——拦截器通过(token 合法),`mustOwn` 发现归属不符 → 404 → 删除不会发生。线程池下一个请求进来前,afterCompletion 已清空 ThreadLocal,不会读到上一个用户的 uid。

**选读证据**:`AuthService.java`、`AuthController.java`(登录签发);`UserContext.java`。

**暂缓阅读**:无。

---

## 第 11 课 评测回归:怎么证明改链路没有变差 【已展开】

**场景与目标**:两条引擎共存的主要目的之一是 A/B 评测。读完应能说出评测器怎么跑、指标是什么、结果落在哪。

**主链**:`mvn test -Dtest=Nl2SqlEvalRunner` → 读 `src/test/resources` 评测 CSV → 逐题调 `Nl2SqlService.answer`(engine 由 `BOVIN_CHAT_ENGINE` 环境变量切换)→ 断言预期 → 输出逐题结果与汇总。

**必读顺序**:

1. `src/test/java/com/eighthours/bovinbi/evaluation/Nl2SqlEvalRunner.java` — 头部注释(用法/引擎切换)+ 主流程:CSV 装载、逐题调用、`AnswerPayload` 与预期比对、写 `eval-runs/*.md`。
2. `eval-report.md` 与 `eval-rules-baseline.md`/`eval-runs/AB-SUMMARY.md` — 历史结果:pipeline 规则引擎 123 条结构准确率 100%;agent 与 pipeline 的对比摘要。
3. `src/main/java/com/eighthours/bovinbi/agent/AgentOrchestrator.java` 第 47 行注释 — 评测单轮跑批生成一次性 memoryId,防串扰:评测正确性依赖这个细节。

**重要分支**:评测走的是与线上同一个 `Nl2SqlService.answer`——评测的不是孤立函数而是整条链(含缓存,注意评测时缓存未命中路径的占比)。

**完整流程串联**:改完提示词或降级链后跑一遍 baseline 与 agent 两组,对比准确率/成本/耗时,把结果落到 `eval-runs/`——"取值由评测数据决定,而非拍脑袋"这句话的证据链。

**选读证据**:`src/test/resources` 下的评测集 CSV;`scripts/` 目录。

**暂缓阅读**:无。

---

## 流程回顾(自述任务)

学完后,请不看书、只对着源码树复述一遍:

1. 从 `ChatController.ask` 出发,把 pipeline 模式下"近12个月每月产奶量趋势"这一问的完整旅程讲到 `AnswerPayload` 返回,途中指出时间区间在哪产生、缓存 key 怎么拼、SQL 在哪被改写(LIMIT)、engine 字段可能取哪些值;
2. 再讲 agent 模式下同一问题的旅程差异:谁持有编排权、预算在哪扣减、final_sql 怎么变成结果、失败后落到哪;
3. 最后回答:两条引擎共享哪些组件?(提示:SchemaLinker、SqlGuard、QueryExecutor、ChartAdvisor、SemanticCache、TimeRangeParser——指出每个共享点在两侧的调用位置。)
