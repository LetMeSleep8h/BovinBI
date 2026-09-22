package com.eighthours.bovinbi.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.agent.AgentTrace;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.ChartSpec;
import com.eighthours.bovinbi.dto.ColInfo;
import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.dto.SemanticParseInfo;
import com.eighthours.bovinbi.entity.ChatMessage;
import com.eighthours.bovinbi.entity.ChatSession;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.entity.DatasetField;
import com.eighthours.bovinbi.entity.QueryLog;
import com.eighthours.bovinbi.mapper.ChatMessageMapper;
import com.eighthours.bovinbi.mapper.ChatSessionMapper;
import com.eighthours.bovinbi.mapper.DatasetFieldMapper;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.mapper.QueryLogMapper;
import com.eighthours.bovinbi.request.ChatExecuteReq;
import com.eighthours.bovinbi.request.ChatParseReq;
import com.eighthours.bovinbi.response.ChatParseResp;
import com.eighthours.bovinbi.dto.ParseState;
import com.eighthours.bovinbi.security.UserContext;
import com.eighthours.bovinbi.service.ChatQueryService;
import com.eighthours.bovinbi.service.TimeRange;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.UserMessage;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.function.Function;

/**
 * 问答两段式(parse / execute)的「平铺教学版」实现:
 * 整条链路的逻辑全部展开在 parse() 与 execute() 两个方法体内,不调用项目里任何现成业务服务
 * (Nl2SqlService / SqlGuard / RuleSqlGenerator / LlmSqlGenerator / SchemaLinker / SemanticCache /
 * ChartAdvisor / AgentOrchestrator / BovinAgent...),Agent 也当场用 LangChain4j 重新装配,
 * 目的是让整条链路可以在一个文件里从头读到尾。
 *
 * 只注入三类「非逻辑」基础设施:MyBatis-Plus Mapper(数据访问)、JdbcTemplate(JDBC 通道)、
 * BovinProperties / ObjectMapper(配置与 JSON)。TimeRange / ColInfo / ChartSpec 等纯数据类型照常引用。
 *
 * 与原版组件的对照阅读地图:
 *   parse():    ChatService.ask(会话/消息落库) → ChitChatHandler(第3步) → TimeRangeParser(第4步)
 *               → SemanticCache(第5步) → SchemaLinker(第6步)
 *               → LlmSqlGenerator+SqlGuard(第7步b) / AgentConfig+BovinTools(第7步a,当场装配)
 *               / RuleSqlGenerator(第7步c 兜底)
 *   execute():  QueryExecutor(第3步) → ChartAdvisor(第4步) → SemanticCache 写入(第5步)
 *               → ChatService.ask 的落库与 query_log 审计(第6步)
 *
 * 刻意接受的代价(生产版应收敛回组件复用):
 * - SQL 守护与 JDBC 执行代码在 parse / execute / Agent 工具里各有一份拷贝(为了平铺可读);
 * - LLM 模型实例每次请求当场构建(原版 LangChain4jClient 用双检锁懒加载复用);
 * - parse 暂存区与语义缓存是进程内 Map(原版 SemanticCache 用 Caffeine;生产应换 Redis);
 * - 两段式里 parse 不查库,因此 LLM 的"自修复"只能修复守护拒绝,修复不了执行报错
 *   (Agent 模式例外:它的工具循环本来就要执行 SQL,修复语义完整保留在工具里);
 * - Agent 的 getSchema 直接返回第 6 步召回结果,final_sql 未命中已执行登记时直接降级规则
 *   (原版 AgentOrchestrator 会重新守护执行一次)。
 */
@Slf4j
@Service
public class ChatQueryServiceImpl implements ChatQueryService {

    // ==================== 基础设施(不含业务逻辑) ====================

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final QueryLogMapper queryLogMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetFieldMapper fieldMapper;
    private final JdbcTemplate dwhJdbcTemplate;
    private final BovinProperties props;
    private final ObjectMapper objectMapper;

    public ChatQueryServiceImpl(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper,
                                QueryLogMapper queryLogMapper, DatasetMapper datasetMapper,
                                DatasetFieldMapper fieldMapper, JdbcTemplate dwhJdbcTemplate,
                                BovinProperties props, ObjectMapper objectMapper) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.queryLogMapper = queryLogMapper;
        this.datasetMapper = datasetMapper;
        this.fieldMapper = fieldMapper;
        this.dwhJdbcTemplate = dwhJdbcTemplate;
        this.props = props;
        this.objectMapper = objectMapper;
    }

    // ==================== 状态区:parse 暂存与语义缓存(进程内) ====================

    /** parse 产物暂存:queryId → 候选解析。execute 一次性消费(remove),防重放;过期即失效 */
    private final Map<Long, StoredParse> parseStore = new ConcurrentHashMap<>();
    /** 语义缓存:key = 数据集|归一化问题|时间区间 → 完整回答(命中则整段免 LLM/免查库) */
    private final Map<String, CacheEntry> semanticCache = new ConcurrentHashMap<>();
    private final AtomicLong queryIdGen = new AtomicLong(System.currentTimeMillis());

    private record StoredParse(Long userId, Long sessionId, Long datasetId, String question,
                               List<SemanticParseInfo> candidates, AnswerPayload cached,
                               String cacheKey, List<AgentTrace.ToolCall> trace, long bornAt) {
    }

    private record CacheEntry(AnswerPayload payload, long bornAt) {
    }

    // ==================== 提示词(原版在 resources/prompts/*.md,这里内联成常量) ====================

    private static final String NL2SQL_PROMPT = """
            #Role: 你是智慧牧场数仓的 NL2SQL 引擎,熟悉奶牛养殖业务与 MySQL 方言。

            #Task: 将用户的中文分析问题翻译成一条可执行的 MySQL SELECT 语句。

            #Rules(违反即失败):
            1. 只能输出一个 JSON 对象,格式:{"sql": "...", "explanation": "一句话中文解释"},不要输出任何其他内容。
            2. 只允许一条 SELECT 语句;禁止 INSERT/UPDATE/DELETE/DDL;禁止分号;禁止注释。
            3. SQL 中的表和列必须来自"召回的 Schema",禁止编造表名/列名(硬约束:DO NOT hallucinate)。
            4. 必须使用表别名与固定 JOIN 写法:
               `FROM dwh_fact_milk m LEFT JOIN dwh_dim_cattle c ON m.cattle_id = c.id LEFT JOIN dwh_dim_farm f ON m.farm_id = f.id`
            5. 聚合口径:产奶量=SUM(m.milk_yield)、平均单产=AVG(m.milk_yield)、泌乳牛数=COUNT(DISTINCT m.cattle_id)、乳脂率=AVG(m.fat_rate)、乳蛋白率=AVG(m.protein_rate)。
            6. SideInfo 已给出时间解析结果:有区间则生成 `m.record_date >= '起始日' AND m.record_date < '结束日'`(半开区间);未给区间则不加时间条件。
            7. 时间粒度:月用 `DATE_FORMAT(m.record_date, '%Y-%m') AS 月份`;天用 `'%Y-%m-%d' AS 日期`。
            8. 结果末尾必须有 `LIMIT`,上限 1000;TopN 类问题按指标 `ORDER BY ... DESC LIMIT N`,最差/最低类用 ASC。
            9. 除法一律用 `NULLIF(x, 0)` 防除零;均值与率类指标用 `ROUND(..., 2)`。
            10. 输出列必须使用中文别名,如 `AS 产奶量`。

            #Exemplars(少样本):

            问题: 近12个月每月产奶量趋势
            {"sql": "SELECT DATE_FORMAT(m.record_date, '%Y-%m') AS 月份, ROUND(SUM(m.milk_yield), 2) AS 产奶量 FROM dwh_fact_milk m LEFT JOIN dwh_dim_cattle c ON m.cattle_id = c.id LEFT JOIN dwh_dim_farm f ON m.farm_id = f.id GROUP BY 1 ORDER BY 1 LIMIT 1000", "explanation": "按自然月统计产奶量趋势"}

            问题: 今年产奶量最高的前5个牧场
            {"sql": "SELECT f.farm_name AS 牧场, ROUND(SUM(m.milk_yield), 2) AS 产奶量 FROM dwh_fact_milk m LEFT JOIN dwh_dim_cattle c ON m.cattle_id = c.id LEFT JOIN dwh_dim_farm f ON m.farm_id = f.id WHERE m.record_date >= '2026-01-01' AND m.record_date < '2027-01-01' GROUP BY 1 ORDER BY 2 DESC LIMIT 5", "explanation": "今年产奶量Top5牧场"}""";

    private static final String AGENT_PROMPT = """
            #Role: 你是智慧牧场数仓的数据分析 Agent,通过调用工具自主完成"自然语言 → SQL → 结果"的全过程。

            #Task: 针对用户问题,自主规划工具调用顺序,产出一条已成功执行的查询 SQL 与一句话中文结论。

            #Tools(按需调用,注意预算上限):
            - getSchema():获取表结构与字段业务口径。写任何 SQL 之前必须先调用本工具。
            - getColumnValues(table, column, keyword):查询维度字段在库中的真实可选值,防止编造 WHERE 条件。
            - executeSql(sql):执行只读 SELECT,返回结果预览;失败会返回报错原因,阅读后修正重试(SQL 执行有次数上限,不要浪费)。

            #Workflow:
            1. 先调用 getSchema 了解表结构;
            2. 问题包含具体维度值(牧场/品种/地区/阶段等)且不确定库中准确写法时,先调用 getColumnValues 确认;
            3. 编写 SQL 并调用 executeSql;若报错,按报错修正后重试(常见修法:列名以 Schema 为准、聚合条件放 HAVING 而非 WHERE);
            4. executeSql 返回"执行成功"后即可收尾,禁止为了"再看一眼"空耗预算;
            5. 最终回答只输出一个 JSON 对象:{"final_sql": "<你通过 executeSql 成功执行的那条 SQL 原文,一字不改>", "explanation": "<一句话中文结论>"}

            #SQL 硬约束(违反会被守护直接拒绝):
            1. 只允许单条 SELECT;禁止分号与注释。
            2. 表和列必须来自 getSchema 返回的 Schema,禁止编造(DO NOT hallucinate)。
            3. 固定 JOIN 写法:
               `FROM dwh_fact_milk m LEFT JOIN dwh_dim_cattle c ON m.cattle_id = c.id LEFT JOIN dwh_dim_farm f ON m.farm_id = f.id`
            4. #SideInfo 已给出确定性时间解析结果:有区间则生成 `m.record_date >= '起始日' AND m.record_date < '结束日'`(半开区间);无区间不加时间条件。
            5. 结果必须有 LIMIT;除法用 NULLIF(x, 0);输出列用中文别名。""";

    /** Agent 契约接口:AiServices 动态代理(与 BovinAgent 同构,这里当场声明、当场装配) */
    public interface InlineAgent {
        String chat(@MemoryId long memoryId, @UserMessage String message);
    }

    // =========================================================================================
    // parse:理解问题 → 生成并守护 SQL(不查库) → 暂存候选,等 execute 来取
    // =========================================================================================
    @Override
    public ChatParseResp parse(ChatParseReq req) {
        Long queryId = null;
        long t0 = System.currentTimeMillis();
        try {
            // ---------- 第 0 步:会话校验(越权防线:会话必须属于当前登录用户)+ 数据集确定 ----------
            ChatSession session = sessionMapper.selectById(req.getSessionId());
            if (session == null || !session.getUserId().equals(UserContext.uid())) {
                throw new BizException(404, "会话不存在");
            }
            Long datasetId = req.getDatasetId() != null ? req.getDatasetId() : session.getDatasetId();
            String question = req.getQuestion() == null ? "" : req.getQuestion().trim();
            if (question.isBlank()) {
                throw new BizException(400, "问题不能为空");
            }

            // ---------- 第 1 步:queryId(首轮空则服务端生成)+ 会话标题 + 用户消息落库 ----------
            queryId = req.getQueryId() != null ? req.getQueryId() : queryIdGen.incrementAndGet();
            if (session.getTitle() == null || session.getTitle().isBlank()) {
                ChatSession upd = new ChatSession();
                upd.setId(session.getId());
                upd.setTitle(question.length() > 20 ? question.substring(0, 20) : question);
                sessionMapper.updateById(upd);
            }
            ChatMessage userMsg = new ChatMessage();
            userMsg.setSessionId(session.getId());
            userMsg.setRole("USER");
            userMsg.setContent(question);
            messageMapper.insert(userMsg);

            // ---------- 第 2 步:parse 暂存区过期清理(演示版:进程内 Map + 时间戳) ----------
            long ttlMs = props.getCache().getTtlSeconds() * 1000;
            parseStore.entrySet().removeIf(e -> System.currentTimeMillis() - e.getValue().bornAt() > ttlMs);

            // ---------- 第 3 步:闲聊分流(短句 + 特征词,且不含数据信号词,防止误伤正常取数) ----------
            String chitChatAnswer = null;
            if (question.length() <= 50) {
                String lower = question.toLowerCase();
                if (Pattern.compile("(你能|你会)(做|干)(什么|啥)|(你|您)(是|叫)(谁|什么)|你好|您好|哈喽|hello|hi|嗨|在吗|谢谢|多谢|感谢|再见|拜拜").matcher(lower).find()
                        && !Pattern.compile("产奶量|奶量|乳脂率|乳蛋白|单产|泌乳|挤奶|牛只|牛数|头数|牧场|品种|牛舍|胎次"
                        + "|趋势|top|排行|前\\d|占比|份额|环比|同比|地区|规模|季度|每月|按月|每天|每日|按天|近\\d|多少|几个|几种|最多|最少|最高|最低|排名|\\d{4}年").matcher(lower).find()) {
                    chitChatAnswer = (question.contains("谢谢") || question.contains("多谢") || question.contains("感谢"))
                            ? "不客气!还想看什么数据,直接问就行。"
                            : (question.contains("再见") || question.contains("拜拜")) ? "再见!数据随时在这里等你。"
                            : "我是 BovinBI 数据分析助手,专门回答「牧场养殖分析」数据集的问题:"
                            + "产奶量/平均单产/乳脂率/乳蛋白率/泌乳牛数等指标,支持趋势、TopN 排行、占比、环比同比、维度分组。"
                            + "试试:近12个月每月产奶量趋势 / 产奶量Top10牧场 / 上个月各品种产奶量占比。";
                }
            }

            // ---------- 第 4 步:时间解析(确定性规则,半开区间 [start, end);识别不到为 null) ----------
            LocalDate today = LocalDate.now();
            TimeRange timeRange = null;
            Matcher m;
            if ((m = Pattern.compile("近(\\d{1,3})天|最近(\\d{1,3})天").matcher(question)).find()) {
                int n = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2));
                timeRange = new TimeRange(today.minusDays(n - 1L), today.plusDays(1), "近" + n + "天");
            } else if ((m = Pattern.compile("近(\\d{1,2})个月|最近(\\d{1,2})个月").matcher(question)).find()) {
                int n = Integer.parseInt(m.group(1));
                timeRange = new TimeRange(today.minusMonths(n - 1L).withDayOfMonth(1), today.plusDays(1), "近" + n + "个月");
            } else if (question.contains("最近一周") || question.contains("近一周") || question.contains("本周") || question.contains("这周")) {
                timeRange = new TimeRange(today.minusDays(6), today.plusDays(1), "最近一周");
            } else if (question.contains("上季度") || question.contains("上个季度")) {
                int curQ = (today.getMonthValue() - 1) / 3;
                LocalDate qs = LocalDate.of(today.getYear(), curQ * 3 + 1, 1).minusMonths(3);
                timeRange = new TimeRange(qs, qs.plusMonths(3), qs.getYear() + "年Q" + (curQ == 0 ? 4 : curQ));
            } else if (question.contains("本季度") || question.contains("这个季度")) {
                int sm = (today.getMonthValue() - 1) / 3 * 3 + 1;
                LocalDate s = LocalDate.of(today.getYear(), sm, 1);
                timeRange = new TimeRange(s, s.plusMonths(3), today.getYear() + "年Q" + ((sm - 1) / 3 + 1));
            } else if ((m = Pattern.compile("(\\d{4})年第?([一二三四1-4])季度").matcher(question)).find()) {
                int q = "一二三四".contains(m.group(2)) ? "一二三四".indexOf(m.group(2)) + 1 : Integer.parseInt(m.group(2));
                LocalDate s = LocalDate.of(Integer.parseInt(m.group(1)), (q - 1) * 3 + 1, 1);
                timeRange = new TimeRange(s, s.plusMonths(3), m.group(1) + "年Q" + q);
            } else if ((m = Pattern.compile("(\\d{4})年(\\d{1,2})月").matcher(question)).find()) {
                YearMonth ym = YearMonth.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
                timeRange = new TimeRange(ym.atDay(1), ym.plusMonths(1).atDay(1), ym.getYear() + "年" + ym.getMonthValue() + "月");
            } else if (question.contains("上个月") || question.contains("上月")) {
                YearMonth ym = YearMonth.from(today).minusMonths(1);
                timeRange = new TimeRange(ym.atDay(1), ym.plusMonths(1).atDay(1), ym.getYear() + "年" + ym.getMonthValue() + "月");
            } else if (question.contains("本月") || question.contains("这个月")) {
                YearMonth ym = YearMonth.from(today);
                timeRange = new TimeRange(ym.atDay(1), ym.plusMonths(1).atDay(1), ym.getYear() + "年" + ym.getMonthValue() + "月");
            } else if (question.contains("去年")) {
                timeRange = new TimeRange(LocalDate.of(today.getYear() - 1, 1, 1), LocalDate.of(today.getYear(), 1, 1), (today.getYear() - 1) + "年");
            } else if ((m = Pattern.compile("(\\d{4})年").matcher(question)).find()) {
                int y = Integer.parseInt(m.group(1));
                timeRange = new TimeRange(LocalDate.of(y, 1, 1), LocalDate.of(y + 1, 1, 1), y + "年");
            } else if (question.contains("今年")) {
                timeRange = new TimeRange(LocalDate.of(today.getYear(), 1, 1), LocalDate.of(today.getYear() + 1, 1, 1), today.getYear() + "年");
            } else if ((m = Pattern.compile("(?<![\\d年])(\\d{1,2})月").matcher(question)).find()) {
                int month = Integer.parseInt(m.group(1));
                if (month >= 1 && month <= 12) { // 未到的月份按"就近原则"理解为去年
                    int year = today.getYear() - (month > today.getMonthValue() ? 1 : 0);
                    YearMonth ym = YearMonth.of(year, month);
                    timeRange = new TimeRange(ym.atDay(1), ym.plusMonths(1).atDay(1), year + "年" + month + "月");
                }
            }

            // ---------- 第 5 步:语义缓存(归一化 key;命中则整段免 LLM/免查库,execute 直接回放缓存载荷) ----------
            String cacheKey = datasetId + "|" + question.toLowerCase()
                    .replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s?？!！。,,;;:：]+", "")
                    + "|" + (timeRange == null ? "ALL" : timeRange.cacheKey());
            AnswerPayload cachedPayload = null;
            CacheEntry entry = semanticCache.get(cacheKey);
            if (entry != null) {
                if (System.currentTimeMillis() - entry.bornAt() <= ttlMs) {
                    cachedPayload = entry.payload();
                    log.info("语义缓存命中(parse): {}", cachedPayload.getSql());
                } else {
                    semanticCache.remove(cacheKey);
                }
            }

            // ---------- 以下三变量是第 7 步各引擎的产出 ----------
            String engine = null;
            String sql = null;          // 守护后的最终 SQL;闲聊/缓存分支另行处理
            String explanation = "";
            List<AgentTrace.ToolCall> trace = List.of();

            if (chitChatAnswer != null) {
                // ===== 闲聊:不进任何引擎,execute 阶段按 sql==null 识别 =====
                engine = "CHIT_CHAT";
                explanation = chitChatAnswer;
            } else if (cachedPayload != null) {
                // ===== 缓存命中:候选 SQL 取缓存里的,execute 直接回放缓放载荷 =====
                engine = "CACHE";
                sql = cachedPayload.getSql();
                explanation = cachedPayload.getExplanation();
            } else {

                // ---------- 第 6 步:Schema 召回(一次查询同时带出:紧凑 Schema 文本 + 表白名单) ----------
                Dataset ds = datasetMapper.selectById(datasetId);
                if (ds == null || ds.getDwhTables() == null || ds.getDwhTables().isBlank()) {
                    throw new BizException("数据集不存在或未配置表白名单");
                }
                Set<String> whitelist = new HashSet<>(Arrays.asList(ds.getDwhTables().toLowerCase().split("[,，\\s]+")));
                List<DatasetField> allFields = fieldMapper.selectList(new LambdaQueryWrapper<DatasetField>()
                        .eq(DatasetField::getDatasetId, datasetId)
                        .eq(DatasetField::getIsHidden, 0)
                        .orderByAsc(DatasetField::getId));
                // 打分:别名命中+3 / 列名命中+2 / 同义词命中+2;命中的字段排前部,LLM 注意力留给最相关列
                record Scored(DatasetField f, int score) {
                }
                List<DatasetField> ordered = allFields.stream().map(f -> {
                            int s = 0;
                            if (f.getAlias() != null && !f.getAlias().isBlank() && question.contains(f.getAlias())) s += 3;
                            if (question.contains(f.getColumnName())) s += 2;
                            if (f.getSynonyms() != null) {
                                for (String syn : f.getSynonyms().split("[,，]")) {
                                    if (!syn.isBlank() && question.contains(syn.trim())) s += 2;
                                }
                            }
                            return new Scored(f, s);
                        }).sorted(Comparator.comparingInt(Scored::score).reversed())
                        .map(Scored::f).toList();
                StringBuilder schemaSb = new StringBuilder();
                schemaSb.append("【数据集】").append(ds.getName()).append(" —— ").append(ds.getDescription()).append('\n');
                schemaSb.append("【可查询物理表】").append(ds.getDwhTables()).append('\n');
                String currentTable = null;
                for (DatasetField f : ordered) {
                    if (!f.getTableName().equals(currentTable)) { // 连续同表字段只打一次表头
                        currentTable = f.getTableName();
                        schemaSb.append("【表 ").append(currentTable).append("】\n");
                    }
                    schemaSb.append("  ").append(f.getTableName()).append('.').append(f.getColumnName())
                            .append(' ').append(f.getDataType()).append(" 业务名:").append(f.getAlias());
                    if ("METRIC".equals(f.getFieldType())) {
                        schemaSb.append("(指标,聚合方式:").append(f.getAggType()).append(')');
                    } else {
                        schemaSb.append("(维度)");
                    }
                    if (f.getSynonyms() != null && !f.getSynonyms().isBlank()) {
                        schemaSb.append(" 同义词:[").append(f.getSynonyms()).append(']');
                    }
                    if (f.getDescription() != null && !f.getDescription().isBlank()) {
                        schemaSb.append(" 口径:").append(f.getDescription());
                    }
                    schemaSb.append('\n');
                }
                String schemaText = schemaSb.toString();

                String sideInfo = "今天日期: " + today + ";时间理解: " + (timeRange == null
                        ? "未识别到明确时间,默认不添加时间过滤"
                        : "已解析为区间 [" + timeRange.start() + ", " + timeRange.endExclusive() + ") 标签:" + timeRange.label());

                boolean llmReady = "openai".equalsIgnoreCase(props.getLlm().getProvider())
                        && !Boolean.TRUE.equals(req.getDisableLlm());

                // ---------- 第 7 步 a:Agent 模式(当场装配,不复用 Spring 的 BovinAgent/BovinTools) ----------
                boolean agentTried = false;
                if (llmReady && "agent".equalsIgnoreCase(props.getChat().getEngine())) {
                    agentTried = true;
                    try {
                        final BovinProperties.Agent acfg = props.getChat().getAgent();
                        final String schemaTextFinal = schemaText;
                        final Set<String> whitelistFinal = whitelist;
                        // 工具对象:匿名类,实例字段直接绑定"本次请求"的状态(白名单/预算/结果登记/轨迹),
                        // 与原版 BovinTools 的 ThreadLocal 方案相比,匿名类捕获天然请求隔离,不需要清理
                        var tools = new Object() {
                            /** 已成功执行的 SQL 登记:归一化(模型原文/守护后) → 守护后 SQL,供最终回填校验 */
                            final Map<String, String> executedSql = new LinkedHashMap<>();
                            final List<AgentTrace.ToolCall> trace = new ArrayList<>();
                            int seq, totalCalls, sqlCalls, schemaCalls, valueCalls;

                            @Tool("获取数据集的表结构与业务字段说明(字段业务名/口径/聚合方式/同义词)。写任何 SQL 之前必须先调用本工具。")
                            public String getSchema() {
                                if (++totalCalls > acfg.getMaxToolCalls()) return "工具调用总预算已耗尽,请立即基于已有信息输出最终 JSON 回答";
                                if (++schemaCalls > acfg.getMaxSchemaCalls()) return "getSchema 调用已达上限,请基于已获取的 Schema 继续";
                                trace.add(new AgentTrace.ToolCall(++seq, "getSchema", "", true, 0, "返回 Schema(" + schemaTextFinal.length() + " 字符)"));
                                return schemaTextFinal;
                            }

                            @Tool("查询某个维度字段在库中的真实可选值(如牧场名/品种/地区),用于生成准确的 WHERE 条件,防止编造维度值。table 与 column 必须来自 Schema。")
                            public String getColumnValues(String tableName, String columnName, String keyword) {
                                if (++totalCalls > acfg.getMaxToolCalls()) return "工具调用总预算已耗尽,请立即基于已有信息输出最终 JSON 回答";
                                if (++valueCalls > acfg.getMaxValueCalls()) return "getColumnValues 调用已达上限";
                                // 标识符白名单校验:只接受简单标识符,从源头杜绝标识符注入
                                if (tableName == null || columnName == null
                                        || !tableName.matches("^[A-Za-z_][A-Za-z0-9_]*$")
                                        || !columnName.matches("^[A-Za-z_][A-Za-z0-9_]*$")) {
                                    return "参数非法:table/column 必须是简单标识符,且来自 Schema";
                                }
                                if (!whitelistFinal.contains(tableName.toLowerCase())) {
                                    return "表不在白名单: " + tableName;
                                }
                                String kw = keyword == null ? "" : keyword.replace("'", "").trim();
                                String vsSql = "SELECT DISTINCT " + columnName + " AS v FROM " + tableName
                                        + (kw.isBlank() ? "" : " WHERE " + columnName + " LIKE '%" + kw + "%'")
                                        + " ORDER BY 1 LIMIT 20";
                                long t0t = System.currentTimeMillis();
                                try {
                                    List<Map<String, Object>> rows = dwhJdbcTemplate.queryForList(vsSql);
                                    String values = rows.stream().map(r -> String.valueOf(r.get("v")))
                                            .reduce((a, b) -> a + " | " + b).orElse("(无数据)");
                                    trace.add(new AgentTrace.ToolCall(++seq, "getColumnValues",
                                            tableName + "." + columnName, true, (int) (System.currentTimeMillis() - t0t),
                                            "返回 " + rows.size() + " 个可选值"));
                                    return tableName + "." + columnName + " 可选值(最多 20 个): " + values;
                                } catch (Exception e) {
                                    trace.add(new AgentTrace.ToolCall(++seq, "getColumnValues",
                                            tableName + "." + columnName, false, (int) (System.currentTimeMillis() - t0t), brief(e.getMessage())));
                                    return "维度值查询失败: " + e.getMessage();
                                }
                            }

                            @Tool("执行一条只读 SELECT 查询并返回结果预览(列名+前若干行)。SQL 会经过安全守护:仅单条 SELECT/表白名单/强制 LIMIT。执行失败会返回报错原因,请阅读报错修正后重试(注意 SQL 执行次数有上限)。")
                            public String executeSql(String sqlParam) {
                                if (++totalCalls > acfg.getMaxToolCalls()) return "工具调用总预算已耗尽,请立即基于已有信息输出最终 JSON 回答";
                                if (++sqlCalls > acfg.getMaxSqlExecutions()) return "SQL 执行次数已达上限(" + acfg.getMaxSqlExecutions() + " 次),请基于已有结果输出最终 JSON 回答";
                                long t0t = System.currentTimeMillis();
                                // ---- 工具内建守护(与第 7 步 b 同规则,当场再写一遍:提示词是建议,工具是法律) ----
                                String guarded;
                                try {
                                    String s = sqlParam == null ? "" : sqlParam.trim();
                                    while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
                                    s = s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*--.*$", " ").trim();
                                    if (s.isEmpty()) return "守护拒绝: SQL 为空";
                                    if (s.contains(";")) return "守护拒绝: 包含多条语句";
                                    net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(s);
                                    if (!(stmt instanceof Select sel)) return "守护拒绝: 仅允许 SELECT 查询";
                                    Set<String> used = new TablesNamesFinder<Void>().getTables(stmt);
                                    for (String t : used) {
                                        String lt = t.replace("`", "").replace("\"", "");
                                        if (lt.contains(".")) lt = lt.substring(lt.lastIndexOf('.') + 1);
                                        if (!whitelistFinal.contains(lt.toLowerCase())) {
                                            return "守护拒绝: SQL 引用了未授权的表 [" + t + "],只使用 Schema 中的表";
                                        }
                                    }
                                    if (sel instanceof PlainSelect ps) {
                                        if (ps.getLimit() == null) {
                                            Limit lim = new Limit();
                                            lim.setRowCount(new LongValue(props.getChat().getMaxRows()));
                                            ps.setLimit(lim);
                                        }
                                        guarded = ps.toString();
                                    } else {
                                        guarded = s.toLowerCase().matches("(?s).*\\blimit\\s+\\d+.*") ? s : s + " LIMIT " + props.getChat().getMaxRows();
                                    }
                                } catch (Exception ge) {
                                    trace.add(new AgentTrace.ToolCall(++seq, "executeSql", brief(sqlParam), false, (int) (System.currentTimeMillis() - t0t), brief(ge.getMessage())));
                                    return "守护拒绝: " + ge.getMessage() + "\n请按硬约束改写后重试(仅单条 SELECT,只使用 Schema 中的表和列)";
                                }
                                // ---- 执行 + 预览(错误即数据:报错以文本回喂模型,驱动其自修复) ----
                                try {
                                    ExecResult r = run(guarded);
                                    String norm1 = sqlParam.toLowerCase().replaceAll("\\s+", " ").trim();
                                    String norm2 = guarded.toLowerCase().replaceAll("\\s+", " ").trim();
                                    executedSql.put(norm1, guarded);
                                    executedSql.put(norm2, guarded);
                                    trace.add(new AgentTrace.ToolCall(++seq, "executeSql", brief(sqlParam), true, (int) (System.currentTimeMillis() - t0t), r.rowCount() + " 行结果"));
                                    StringBuilder pv = new StringBuilder("执行成功:共 ").append(r.rowCount()).append(" 行\n列: ");
                                    pv.append(r.columns().stream().map(ColInfo::name).reduce((a, b) -> a + " | " + b).orElse("")).append('\n');
                                    r.rows().stream().limit(acfg.getPreviewRows()).forEach(row ->
                                            pv.append(row.values().stream().map(v -> v == null ? "" : String.valueOf(v))
                                                    .reduce((a, b) -> a + " | " + b).orElse("")).append('\n'));
                                    return pv.toString();
                                } catch (Exception e) {
                                    trace.add(new AgentTrace.ToolCall(++seq, "executeSql", brief(sqlParam), false, (int) (System.currentTimeMillis() - t0t), brief(e.getMessage())));
                                    return "执行失败: " + root(e) + "\n请修正 SQL 后重试(常见修法:列名以 Schema 为准;聚合条件放 HAVING 而非 WHERE;别名引用改为重复表达式)";
                                }
                            }

                            /** 匿名类内部的执行器:Statement 级超时 + maxRows,值归一化(与 execute() 第 3 步同规则) */
                            private ExecResult run(String sql) {
                                return dwhJdbcTemplate.execute((ConnectionCallback<ExecResult>) conn -> {
                                    try (Statement st = conn.createStatement()) {
                                        st.setQueryTimeout(props.getChat().getQueryTimeoutSeconds());
                                        st.setMaxRows(props.getChat().getMaxRows());
                                        try (ResultSet rs = st.executeQuery(sql)) {
                                            ResultSetMetaData md = rs.getMetaData();
                                            int n = md.getColumnCount();
                                            List<ColInfo> cols = new ArrayList<>();
                                            for (int i = 1; i <= n; i++) cols.add(new ColInfo(md.getColumnLabel(i), md.getColumnTypeName(i)));
                                            List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
                                            while (rs.next()) {
                                                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                                                for (int i = 1; i <= n; i++) row.put(md.getColumnLabel(i), normalize(rs.getObject(i)));
                                                rows.add(row);
                                            }
                                            return new ExecResult(cols, rows, rows.size(), 0);
                                        }
                                    }
                                });
                            }

                            private Object normalize(Object v) {
                                if (v instanceof LocalDateTime ldt) return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                                if (v instanceof LocalDate d) return d.toString();
                                if (v instanceof byte[] b) return "[binary " + b.length + "B]";
                                if (v instanceof BigDecimal bd) return bd.doubleValue();
                                return v;
                            }

                            private String brief(String s) {
                                if (s == null) return "";
                                String one = s.replaceAll("\\s+", " ").trim();
                                return one.length() <= 120 ? one : one.substring(0, 120) + "…";
                            }

                            private String root(Throwable e) {
                                Throwable t = e;
                                while (t.getCause() != null) t = t.getCause();
                                return t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
                            }
                        };

                        // 当场构建模型与 AiServices 代理:工具循环由框架内部驱动
                        // (模型决定调哪个工具 → 执行 @Tool 方法 → 结果回喂 → 直至最终回答),应用侧不手写循环
                        BovinProperties.Llm lc = props.getLlm();
                        ChatLanguageModel agentModel = OpenAiChatModel.builder()
                                .baseUrl(lc.getBaseUrl()).apiKey(lc.getApiKey()).modelName(lc.getModel())
                                .temperature(lc.getTemperature())
                                // Agent 循环专属超时/重试(短超时、零重试):循环自带"报错回喂重试"语义,
                                // HTTP 层重试会把耗时叠加到多轮循环上,慢端点下拖垮整次问答
                                .maxRetries(acfg.getLlmMaxRetries())
                                .timeout(Duration.ofSeconds(acfg.getLlmTimeoutSeconds()))
                                .logRequests(lc.isLogRequests()).logResponses(lc.isLogResponses())
                                .build();
                        InlineAgent agent = AiServices.builder(InlineAgent.class)
                                .chatLanguageModel(agentModel)
                                .systemMessageProvider(memoryId -> AGENT_PROMPT)
                                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                                        .id(memoryId)
                                        .maxMessages(acfg.getMemoryMessages())
                                        .build())
                                .tools(tools)
                                .build();

                        String agentRaw = agent.chat(session.getId(), "#SideInfo: " + sideInfo + "\n#Question: " + question);

                        // 解析最终 JSON:{"final_sql": "...", "explanation": "..."}
                        JsonNode aj;
                        try {
                            aj = objectMapper.readTree(agentRaw);
                        } catch (Exception ignore) {
                            Matcher jm = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(agentRaw);
                            aj = null;
                            if (jm.find()) {
                                try {
                                    aj = objectMapper.readTree(jm.group());
                                } catch (Exception ignore2) {
                                }
                            }
                        }
                        if (aj == null) throw new BizException(502, "无法从 Agent 回复中解析出 JSON");
                        String finalSql = aj.path("final_sql").asText("").trim();
                        if (finalSql.isEmpty()) throw new BizException(502, "Agent 最终回答缺少 final_sql");
                        // 信任但校验:final_sql 必须命中"已成功执行"的登记(防模型改写/幻觉;
                        // 演示版未命中直接降级,原版 AgentOrchestrator 会重新守护执行一次)
                        String guardedFinal = tools.executedSql.get(finalSql.toLowerCase().replaceAll("\\s+", " ").trim());
                        if (guardedFinal == null) throw new BizException(502, "final_sql 未命中已执行登记(模型改写或幻觉)");
                        engine = "AGENT";
                        sql = guardedFinal;
                        explanation = aj.path("explanation").asText("");
                        trace = tools.trace;
                    } catch (Exception e) {
                        log.warn("Agent 链路失败,降级规则引擎: {}", e.getMessage());
                    }
                }

                // ---------- 第 7 步 b:LLM 管线(生成 → 守护 → 失败自修复一次;仍失败落到规则) ----------
                boolean llmTried = false;
                if (sql == null && llmReady && !agentTried) {
                    llmTried = true;
                    String lastRawSql = null;
                    String lastError = null;
                    for (int attempt = 0; attempt < 2 && sql == null; attempt++) {
                        try {
                            String userPrompt = "#Schema: " + schemaText + "\n#SideInfo: " + sideInfo + "\n#Question: " + question
                                    + (attempt == 0 ? "" : "\n\n### 你上次生成的 SQL(守护被拒)\n```sql\n" + lastRawSql
                                    + "\n```\n### 拒绝原因\n" + lastError
                                    + "\n\n请修正这条 SQL(注意:只使用 Schema 中的表和列),严格按照系统约束输出 JSON。");
                            // 当场构建模型(演示每请求新建;生产应懒加载复用,参照 LangChain4jClient 双检锁)
                            BovinProperties.Llm lc = props.getLlm();
                            ChatLanguageModel model = OpenAiChatModel.builder()
                                    .baseUrl(lc.getBaseUrl()).apiKey(lc.getApiKey()).modelName(lc.getModel())
                                    .temperature(lc.getTemperature()).maxRetries(lc.getMaxRetries())
                                    .timeout(Duration.ofSeconds(lc.getTimeoutSeconds()))
                                    .logRequests(lc.isLogRequests()).logResponses(lc.isLogResponses())
                                    .build();
                            String raw = model.generate(List.of(
                                    SystemMessage.from(NL2SQL_PROMPT),
                                    dev.langchain4j.data.message.UserMessage.from(userPrompt))).content().text();
                            // 解析模型回复(容忍 markdown 代码块包裹),sql 为空视为失败
                            JsonNode json;
                            try {
                                json = objectMapper.readTree(raw);
                            } catch (Exception ignore) {
                                Matcher jm = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(raw);
                                json = null;
                                if (jm.find()) {
                                    try {
                                        json = objectMapper.readTree(jm.group());
                                    } catch (Exception ignore2) {
                                    }
                                }
                            }
                            if (json == null) throw new BizException(502, "无法从模型回复中解析出 JSON");
                            String rawSql = json.path("sql").asText("").trim();
                            if (rawSql.isEmpty()) throw new BizException(502, "模型未返回 SQL");
                            lastRawSql = rawSql;
                            // ---- SQL 守护:去注释/分号 → AST → 仅 SELECT → 表白名单 → 强制 LIMIT ----
                            String s = rawSql.trim();
                            while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
                            s = s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*--.*$", " ").trim();
                            if (s.isEmpty()) throw new BizException("生成的 SQL 为空");
                            if (s.contains(";")) throw new BizException("拒绝执行:包含多条语句");
                            net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(s);
                            if (!(stmt instanceof Select sel)) throw new BizException("安全策略:仅允许 SELECT 查询");
                            Set<String> used = new TablesNamesFinder<Void>().getTables(stmt);
                            if (used.isEmpty()) throw new BizException("拒绝执行:未识别到目标表");
                            for (String t : used) {
                                String lt = t.replace("`", "").replace("\"", "");
                                if (lt.contains(".")) lt = lt.substring(lt.lastIndexOf('.') + 1);
                                if (!whitelist.contains(lt.toLowerCase())) {
                                    throw new BizException(403, "安全策略:SQL 引用了未授权的表 [" + t + "]");
                                }
                            }
                            if (sel instanceof PlainSelect ps) {
                                if (ps.getLimit() == null) {
                                    Limit lim = new Limit();
                                    lim.setRowCount(new LongValue(props.getChat().getMaxRows()));
                                    ps.setLimit(lim);
                                }
                                sql = ps.toString();
                            } else {
                                // 集合操作(UNION 等)直接字符串追加 LIMIT
                                sql = s.toLowerCase().matches("(?s).*\\blimit\\s+\\d+.*") ? s : s + " LIMIT " + props.getChat().getMaxRows();
                            }
                            explanation = json.path("explanation").asText("");
                            engine = attempt == 0 ? "LLM" : "LLM(修复)";
                        } catch (Exception e) {
                            if (!props.getChat().isFallbackToRule()) {
                                throw e instanceof BizException biz ? biz : new BizException(502, e.getMessage());
                            }
                            log.warn("LLM 第 {} 轮失败: {}", attempt + 1, e.getMessage());
                            lastError = e.getMessage();
                        }
                    }
                }

                // ---------- 第 7 步 c:规则引擎(mock 主引擎 / disableLlm 主引擎 / LLM 与 Agent 的兜底) ----------
                if (sql == null) {
                    // 固定口径:fact_milk ⋈ dim_cattle ⋈ dim_farm(注意模板里的 % 一律放在参数里,避免 formatted() 冲突)
                    final String FROM = "FROM dwh_fact_milk m\nLEFT JOIN dwh_dim_cattle c ON m.cattle_id = c.id\nLEFT JOIN dwh_dim_farm f ON m.farm_id = f.id";
                    String q = question;
                    String ql = q.toLowerCase();

                    // 维度值过滤(→ WHERE 条件)
                    final String valueFilter =
                            q.contains("西北") ? "f.region = '西北'" : q.contains("华北") ? "f.region = '华北'"
                                    : q.contains("华东") ? "f.region = '华东'" : q.contains("东北") ? "f.region = '东北'"
                                    : q.contains("华中") ? "f.region = '华中'" : q.contains("西南") ? "f.region = '西南'"
                                    : q.contains("大型") ? "f.scale = '大型'" : q.contains("中型") ? "f.scale = '中型'"
                                    : q.contains("小型") ? "f.scale = '小型'"
                                    : q.contains("荷斯坦") ? "c.breed = '荷斯坦'" : q.contains("西门塔尔") ? "c.breed = '西门塔尔'"
                                    : q.contains("娟姗") ? "c.breed = '娟姗'"
                                    : (q.contains("泌乳初期") || q.contains("产犊初期")) ? "m.lactation_stage = '泌乳初期'"
                                    : q.contains("泌乳中期") ? "m.lactation_stage = '泌乳中期'"
                                    : q.contains("泌乳后期") ? "m.lactation_stage = '泌乳后期'"
                                    : q.contains("周末") ? "DAYOFWEEK(m.record_date) IN (1, 7)"
                                    : q.contains("工作日") ? "DAYOFWEEK(m.record_date) NOT IN (1, 7)" : null;

                    // 指标识别(长词优先,防止"乳蛋白率"被"蛋白率"抢先、"平均单产"被"产奶量"抢先):{别名, 表达式}
                    String[] metric = null;
                    if (q.contains("乳蛋白率") || q.contains("蛋白率")) metric = new String[]{"乳蛋白率", "ROUND(AVG(m.protein_rate), 2)"};
                    else if (q.contains("乳脂率") || q.contains("脂肪率")) metric = new String[]{"乳脂率", "ROUND(AVG(m.fat_rate), 2)"};
                    else if (q.contains("单产")) metric = new String[]{"平均单产", "ROUND(AVG(m.milk_yield), 2)"};
                    else if (q.contains("泌乳牛数") || q.contains("牛只数") || q.contains("牛的数量") || q.contains("头数")
                            || q.contains("牛数") || q.contains("泌乳牛") || q.contains("在泌牛")) metric = new String[]{"泌乳牛数", "COUNT(DISTINCT m.cattle_id)"};
                    else if (q.contains("产奶量") || q.contains("奶量") || q.contains("产奶") || q.contains("挤奶量") || q.contains("产量")) metric = new String[]{"产奶量", "ROUND(SUM(m.milk_yield), 2)"};
                    String[] m0 = metric != null ? metric : new String[]{"产奶量", "ROUND(SUM(m.milk_yield), 2)"}; // 默认产奶量

                    // 维度识别:{别名, 表达式}
                    String[] dim = null;
                    if (q.contains("牧场规模") || q.contains("规模")) dim = new String[]{"牧场规模", "f.scale"};
                    else if (q.contains("牧场") || q.contains("农场") || q.contains("基地")) dim = new String[]{"牧场", "f.farm_name"};
                    else if (q.contains("地区") || q.contains("区域") || q.contains("大区")) dim = new String[]{"地区", "f.region"};
                    else if (q.contains("品种") || q.contains("牛种")) dim = new String[]{"品种", "c.breed"};
                    else if (q.contains("泌乳阶段") || q.contains("泌乳期")) dim = new String[]{"泌乳阶段", "m.lactation_stage"};
                    else if (q.contains("牛舍") || q.contains("栏舍") || q.contains("牛栏")) dim = new String[]{"牛舍", "c.barn"};
                    else if (q.contains("胎次")) dim = new String[]{"胎次", "c.parity"};
                    else if (q.contains("工作日") || q.contains("周末") || q.contains("星期") || q.contains("周几"))
                        dim = new String[]{"星期类型", "CASE WHEN DAYOFWEEK(m.record_date) IN (1,7) THEN '周末' ELSE '工作日' END"};
                    else if (q.contains("季度")) dim = new String[]{"季度", "CONCAT(YEAR(m.record_date), '-Q', QUARTER(m.record_date))"};
                    String[] d0 = dim != null ? dim : new String[]{"牧场", "f.farm_name"};

                    // TopN 识别
                    int topN = 10;
                    if ((m = Pattern.compile("(?:top|TOP|Top)\\s*(\\d{1,2})").matcher(q)).find()) topN = Integer.parseInt(m.group(1));
                    else if ((m = Pattern.compile("前\\s*(\\d{1,2})").matcher(q)).find()) topN = Integer.parseInt(m.group(1));
                    else if ((m = Pattern.compile("(\\d{1,2})\\s*[名个头栏栋]").matcher(q)).find()) topN = Integer.parseInt(m.group(1));

                    // WHERE 组装器(时间区间 + 维度值过滤,半开区间利于索引)
                    Function<TimeRange, String> whereOf = tr -> {
                        String w = tr == null ? "" : "m.record_date >= '" + TimeRange.F.format(tr.start())
                                + "' AND m.record_date < '" + TimeRange.F.format(tr.endExclusive()) + "'";
                        if (valueFilter != null) w = w.isEmpty() ? valueFilter : w + " AND " + valueFilter;
                        return w.isEmpty() ? "" : " WHERE " + w;
                    };

                    String rawSql = null;
                    String ruleExplanation = "";
                    if (q.contains("环比")) {
                        YearMonth lastMonth = YearMonth.from(today).minusMonths(1);
                        TimeRange cur = timeRange != null ? timeRange
                                : new TimeRange(lastMonth.atDay(1), lastMonth.plusMonths(1).atDay(1),
                                lastMonth.getYear() + "年" + lastMonth.getMonthValue() + "月");
                        LocalDate prevFirst = cur.start().withDayOfMonth(1).minusMonths(1);
                        TimeRange prev = new TimeRange(prevFirst, prevFirst.plusMonths(1),
                                prevFirst.getYear() + "年" + prevFirst.getMonthValue() + "月");
                        rawSql = ("SELECT '%s' AS 期间, %s AS %s\n%s\n%s\nUNION ALL\nSELECT '%s' AS 期间, %s AS %s\n%s\n%s\nLIMIT 1000")
                                .formatted(cur.label(), m0[1], m0[0], FROM, whereOf.apply(cur),
                                        prev.label(), m0[1], m0[0], FROM, whereOf.apply(prev));
                        ruleExplanation = "对比 " + cur.label() + " 与 " + prev.label() + " 的" + m0[0];
                    } else if (q.contains("同比")) {
                        TimeRange base = timeRange != null ? timeRange : new TimeRange(
                                YearMonth.from(today).minusMonths(1).atDay(1), YearMonth.from(today).atDay(1), "上个月");
                        TimeRange lastYear = new TimeRange(base.start().minusYears(1), base.endExclusive().minusYears(1), base.label() + "(去年同期)");
                        rawSql = ("SELECT '%s' AS 期间, %s AS %s\n%s\n%s\nUNION ALL\nSELECT '%s' AS 期间, %s AS %s\n%s\n%s\nLIMIT 1000")
                                .formatted(base.label(), m0[1], m0[0], FROM, whereOf.apply(base),
                                        lastYear.label(), m0[1], m0[0], FROM, whereOf.apply(lastYear));
                        ruleExplanation = "同比:对比 " + base.label() + " 与去年同期" + m0[0];
                    } else if (ql.contains("top") || q.contains("排行") || q.contains("前10") || q.contains("前5") || q.contains("前3")
                            || q.contains("最高") || q.contains("最好") || q.contains("最差") || q.contains("最少") || q.contains("最低")) {
                        boolean asc = q.contains("最低") || q.contains("最差") || q.contains("最少");
                        rawSql = ("SELECT %s AS %s, %s AS %s\n%s%s\nGROUP BY 1\nORDER BY 2 %s\nLIMIT %d")
                                .formatted(d0[1], d0[0], m0[1], m0[0], FROM, whereOf.apply(timeRange), asc ? "ASC" : "DESC", topN);
                        ruleExplanation = (timeRange == null ? "" : timeRange.label() + " ") + d0[0] + "Top" + topN + "(" + m0[0] + ")" + (asc ? ",升序" : "");
                    } else if (q.contains("占比") || q.contains("份额") || q.contains("构成") || q.contains("分布") || q.contains("结构")) {
                        rawSql = ("SELECT %s AS %s, %s AS %s\n%s%s\nGROUP BY 1\nORDER BY 2 DESC\nLIMIT 1000")
                                .formatted(d0[1], d0[0], m0[1], m0[0], FROM, whereOf.apply(timeRange));
                        ruleExplanation = "按" + d0[0] + "统计" + (timeRange == null ? "" : timeRange.label()) + m0[0];
                    } else if (q.contains("趋势") || q.contains("按月") || q.contains("每月") || q.contains("每个月") || q.contains("月度")
                            || q.contains("走势") || q.contains("变化") || q.contains("按天") || q.contains("每天") || q.contains("每日")) {
                        boolean byDay = q.contains("按天") || q.contains("每天") || q.contains("每日");
                        String dateExpr = byDay ? "DATE_FORMAT(m.record_date, '%Y-%m-%d')" : "DATE_FORMAT(m.record_date, '%Y-%m')";
                        String label = byDay ? "日期" : "月份";
                        if (dim != null) { // 时间 × 维度组合,如"每月各牧场产奶量"
                            rawSql = ("SELECT %s AS %s, %s AS %s, %s AS %s\n%s%s\nGROUP BY 1, 2\nORDER BY 1\nLIMIT 1000")
                                    .formatted(dateExpr, label, d0[1], d0[0], m0[1], m0[0], FROM, whereOf.apply(timeRange));
                            ruleExplanation = "按" + label + "×" + d0[0] + "统计" + (timeRange == null ? "" : timeRange.label()) + m0[0];
                        } else {
                            rawSql = ("SELECT %s AS %s, %s AS %s\n%s%s\nGROUP BY 1\nORDER BY 1\nLIMIT 1000")
                                    .formatted(dateExpr, label, m0[1], m0[0], FROM, whereOf.apply(timeRange));
                            ruleExplanation = "按" + label + "统计" + (timeRange == null ? "" : timeRange.label()) + m0[0] + "趋势";
                        }
                    } else if (dim != null && (q.contains("各") || q.contains("每个") || q.contains("按") || q.contains("分别") || q.contains("对比"))) {
                        rawSql = ("SELECT %s AS %s, %s AS %s\n%s%s\nGROUP BY 1\nORDER BY 2 DESC\nLIMIT 1000")
                                .formatted(d0[1], d0[0], m0[1], m0[0], FROM, whereOf.apply(timeRange));
                        ruleExplanation = "按" + d0[0] + "统计" + (timeRange == null ? "" : timeRange.label()) + m0[0];
                    } else if (valueFilter != null) {
                        rawSql = "SELECT " + m0[1] + " AS " + m0[0] + "\n" + FROM + whereOf.apply(timeRange) + "\nLIMIT 1000";
                        ruleExplanation = (timeRange == null ? "" : timeRange.label()) + "满足条件的" + m0[0];
                    } else if (metric != null) {
                        rawSql = "SELECT " + m0[1] + " AS " + m0[0] + "\n" + FROM + whereOf.apply(timeRange) + "\nLIMIT 1000";
                        ruleExplanation = (timeRange == null ? "全部数据" : timeRange.label()) + "的" + m0[0];
                    }

                    if (rawSql != null) {
                        // ---- 规则产物同样过守护(纵深防御:自己生成的 SQL 也不裸奔) ----
                        String s = rawSql.trim();
                        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
                        s = s.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*--.*$", " ").trim();
                        if (s.contains(";")) throw new BizException("拒绝执行:包含多条语句");
                        net.sf.jsqlparser.statement.Statement stmt = CCJSqlParserUtil.parse(s);
                        if (!(stmt instanceof Select sel)) throw new BizException("安全策略:仅允许 SELECT 查询");
                        Set<String> used = new TablesNamesFinder<Void>().getTables(stmt);
                        for (String t : used) {
                            String lt = t.replace("`", "").replace("\"", "");
                            if (lt.contains(".")) lt = lt.substring(lt.lastIndexOf('.') + 1);
                            if (!whitelist.contains(lt.toLowerCase())) {
                                throw new BizException(403, "安全策略:SQL 引用了未授权的表 [" + t + "]");
                            }
                        }
                        if (sel instanceof PlainSelect ps) {
                            if (ps.getLimit() == null) {
                                Limit lim = new Limit();
                                lim.setRowCount(new LongValue(props.getChat().getMaxRows()));
                                ps.setLimit(lim);
                            }
                            sql = ps.toString();
                        } else {
                            sql = s.toLowerCase().matches("(?s).*\\blimit\\s+\\d+.*") ? s : s + " LIMIT " + props.getChat().getMaxRows();
                        }
                        explanation = ruleExplanation;
                        engine = agentTried ? "AGENT(降级RULE)" : llmTried ? "RULE(降级)" : "RULE";
                    }
                }
            }

            // ---------- 第 8 步:所有引擎都给不出 SQL → 优雅降级(state=FAILED,不进暂存区) ----------
            if (sql == null && chitChatAnswer == null && cachedPayload == null) {
                return ChatParseResp.builder().queryId(queryId).state(ParseState.FAILED)
                        .errorMsg("抱歉,我暂时理解不了这个问题。可以试试:每月产奶量趋势 / 产奶量Top10牧场 / 上个月各品种产奶量占比")
                        .costMs(System.currentTimeMillis() - t0).build();
            }

            // ---------- 第 9 步:组装候选 → 暂存 → 返回 ----------
            SemanticParseInfo cand = SemanticParseInfo.builder()
                    .parseId(1).engine(engine).question(question).sql(sql)
                    .explanation(explanation).timeRange(timeRange).datasetId(datasetId)
                    .build();
            List<SemanticParseInfo> candidates = new ArrayList<>(List.of(cand));
            parseStore.put(queryId, new StoredParse(UserContext.uid(), session.getId(), datasetId, question,
                    candidates, cachedPayload, cacheKey, trace, System.currentTimeMillis()));
            return ChatParseResp.builder()
                    .queryId(queryId).state(ParseState.COMPLETED).candidates(candidates)
                    .costMs(System.currentTimeMillis() - t0).build();

        } catch (BizException e) {
            throw e; // 会话不存在/参数非法/安全拒绝:按 HTTP 语义透出给全局异常处理器
        } catch (Exception e) {
            log.warn("parse 失败: {}", e.getMessage());
            return ChatParseResp.builder().queryId(queryId).state(ParseState.FAILED)
                    .errorMsg("解析失败: " + e.getMessage())
                    .costMs(System.currentTimeMillis() - t0).build();
        }
    }

    // =========================================================================================
    // execute:按 queryId+parseId 取回解析结果 → 查库 → 图表推荐 → 落库审计
    // =========================================================================================
    @Override
    public AnswerPayload execute(ChatExecuteReq req) {
        long t0 = System.currentTimeMillis();

        // ---------- 第 1 步:取回解析结果(一次性消费,防重放;SQL 全程不经前端) ----------
        StoredParse stored = req.getQueryId() == null ? null : parseStore.remove(req.getQueryId());
        if (stored == null) {
            throw new BizException(404, "解析结果不存在或已被消费(parse 后 " + props.getCache().getTtlSeconds() + " 秒内有效)");
        }
        // ---------- 第 2 步:越权与一致性校验(用户归属 + 会话归属双保险) ----------
        if (!stored.userId().equals(UserContext.uid())) {
            throw new BizException(403, "无权执行该查询");
        }
        if (req.getSessionId() != null && !req.getSessionId().equals(stored.sessionId())) {
            throw new BizException(404, "会话与解析结果不匹配");
        }
        SemanticParseInfo cand = stored.candidates().stream()
                .filter(c -> c.getParseId() != null && c.getParseId().equals(req.getParseId()))
                .findFirst()
                .orElseThrow(() -> new BizException(404, "候选解析不存在"));

        AnswerPayload payload = new AnswerPayload();
        if (stored.cached() != null) {
            // ---------- 缓存命中路径:parse 已带全量结果,直接回放(显式拷贝字段,避免调用方污染缓存对象) ----------
            AnswerPayload c = stored.cached();
            payload.setSql(c.getSql());
            payload.setExplanation(c.getExplanation());
            payload.setColumns(c.getColumns());
            payload.setRows(c.getRows());
            payload.setRowCount(c.getRowCount());
            payload.setChart(c.getChart());
            payload.setCacheHit(true);
            payload.setEngine("CACHE");
        } else if (cand.getSql() == null) {
            // ---------- 闲聊路径:只有解释文本,无数据 ----------
            payload.setEngine(cand.getEngine());
            payload.setExplanation(cand.getExplanation());
        } else {
            payload.setEngine(cand.getEngine());
            try {
                // ---------- 第 3 步:JDBC 执行(只读通道:Statement 级 queryTimeout + maxRows 双保险) ----------
                ExecResult r = dwhJdbcTemplate.execute((ConnectionCallback<ExecResult>) conn -> {
                    try (Statement st = conn.createStatement()) {
                        st.setQueryTimeout(props.getChat().getQueryTimeoutSeconds());
                        st.setMaxRows(props.getChat().getMaxRows());
                        try (ResultSet rs = st.executeQuery(cand.getSql())) {
                            ResultSetMetaData md = rs.getMetaData();
                            int n = md.getColumnCount();
                            List<ColInfo> cols = new ArrayList<>();
                            for (int i = 1; i <= n; i++) cols.add(new ColInfo(md.getColumnLabel(i), md.getColumnTypeName(i)));
                            List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
                            while (rs.next()) {
                                LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                                for (int i = 1; i <= n; i++) {
                                    Object v = rs.getObject(i);
                                    row.put(md.getColumnLabel(i), v instanceof LocalDateTime ldt
                                            ? ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                                            : v instanceof LocalDate ld ? ld.toString()
                                            : v instanceof byte[] b ? "[binary " + b.length + "B]"
                                            : v instanceof BigDecimal bd ? bd.doubleValue() : v);
                                }
                                rows.add(row);
                            }
                            return new ExecResult(cols, rows, rows.size(), 0);
                        }
                    }
                });

                // ---------- 第 4 步:图表推荐(先按列形态分类,再按 结果形态 × 问题意图 选型) ----------
                List<ColInfo> cols = r.columns();
                List<LinkedHashMap<String, Object>> rows = r.rows();
                Function<Object, Double> toD = v -> v instanceof Number nb ? nb.doubleValue()
                        : v instanceof String vs && vs.matches("-?[\\d.,]+%?")
                        ? Double.parseDouble(vs.replace(",", "").replace("%", "")) : 0.0;
                ChartSpec chart;
                if (rows.isEmpty() || cols.isEmpty()) {
                    chart = new ChartSpec("none", null, null, null, "无数据可绘制");
                } else {
                    int timeIdx = -1, catIdx = -1;
                    List<Integer> numIdx = new ArrayList<>();
                    for (int i = 0; i < cols.size(); i++) {
                        ColInfo c = cols.get(i);
                        String tu = c.type() == null ? "" : c.type().toUpperCase();
                        boolean numericType = tu.contains("INT") || tu.contains("DECIMAL") || tu.contains("NUMERIC")
                                || tu.contains("DOUBLE") || tu.contains("FLOAT") || tu.contains("REAL");
                        boolean numericValues = false;
                        for (int k = 0; k < Math.min(rows.size(), 10); k++) {
                            if (new ArrayList<>(rows.get(k).values()).get(i) instanceof Number) {
                                numericValues = true;
                                break;
                            }
                        }
                        if (numericType || numericValues) {
                            numIdx.add(i);
                            continue;
                        }
                        boolean timeCol = c.name() != null && c.name().matches("(?i).*(date|日期|月份|month|day|week|周|季度|quarter|期间|年份).*");
                        if (!timeCol) {
                            int dh = 0;
                            for (int k = 0; k < Math.min(rows.size(), 5); k++) {
                                Object v = new ArrayList<>(rows.get(k).values()).get(i);
                                if (v instanceof String vs && (vs.matches("\\d{4}-\\d{2}") || vs.matches("\\d{4}-\\d{2}-\\d{2}") || vs.matches("\\d{4}/\\d{2}/\\d{2}"))) dh++;
                            }
                            timeCol = dh >= 2;
                        }
                        if (timeCol && timeIdx < 0) timeIdx = i;
                        else if (catIdx < 0) catIdx = i;
                    }
                    // 单行单指标 → 大数字卡片
                    if (rows.size() == 1 && numIdx.size() == 1 && timeIdx < 0 && catIdx < 0) {
                        chart = new ChartSpec("none", null, null, null, "单值指标,以指标卡呈现");
                    }
                    // 时间 × 类别 × 数值 → 透视成多序列
                    else if (timeIdx >= 0 && catIdx >= 0 && !numIdx.isEmpty() && cols.size() >= 3) {
                        TreeSet<String> xs = new TreeSet<>();
                        Map<String, Map<String, Double>> matrix = new LinkedHashMap<>();
                        for (LinkedHashMap<String, Object> rw : rows) {
                            List<Object> vals = new ArrayList<>(rw.values());
                            xs.add(String.valueOf(vals.get(timeIdx)));
                            matrix.computeIfAbsent(String.valueOf(vals.get(catIdx)), k -> new LinkedHashMap<>())
                                    .put(String.valueOf(vals.get(timeIdx)), toD.apply(vals.get(numIdx.get(0))));
                        }
                        List<ChartSpec.Series> series = new ArrayList<>();
                        for (Map.Entry<String, Map<String, Double>> en : matrix.entrySet()) {
                            if (series.size() >= 8) break;
                            List<Double> dv = new ArrayList<>();
                            for (String tv : xs) dv.add(en.getValue().getOrDefault(tv, 0.0));
                            series.add(new ChartSpec.Series(en.getKey(), dv));
                        }
                        chart = series.size() > 1
                                ? new ChartSpec("line", cols.get(timeIdx).name(), new ArrayList<>(xs), series, "时间×类别交叉,已透视成多序列对比")
                                : new ChartSpec("bar", cols.get(timeIdx).name(), new ArrayList<>(xs), series, "时间×类别数据透视");
                    }
                    // 时间序列 → 折线(最多 3 条序列)
                    else if (timeIdx >= 0 && !numIdx.isEmpty()) {
                        final int tIdx = timeIdx; // lambda 捕获需要 final 副本
                        List<String> x = rows.stream().map(rw -> String.valueOf(new ArrayList<>(rw.values()).get(tIdx))).toList();
                        List<ChartSpec.Series> series = new ArrayList<>();
                        for (int k = 0; k < Math.min(numIdx.size(), 3); k++) {
                            int idx = numIdx.get(k);
                            series.add(new ChartSpec.Series(cols.get(idx).name(),
                                    rows.stream().map(rw -> toD.apply(new ArrayList<>(rw.values()).get(idx))).toList()));
                        }
                        chart = new ChartSpec("line", cols.get(timeIdx).name(), x, series, "X轴为时间维度,折线图最利于观察趋势");
                    }
                    // 类别 + 数值 → 饼图(占比意图且类别≤8)或柱状图
                    else if (catIdx >= 0 && !numIdx.isEmpty()) {
                        final int cIdx = catIdx; // lambda 捕获需要 final 副本
                        List<String> x = rows.stream().map(rw -> String.valueOf(new ArrayList<>(rw.values()).get(cIdx))).toList();
                        int vIdx = numIdx.get(0);
                        List<Double> vals = rows.stream().map(rw -> toD.apply(new ArrayList<>(rw.values()).get(vIdx))).toList();
                        boolean ratioAsked = Pattern.compile("占比|份额|构成|分布|结构|比例").matcher(stored.question()).find();
                        boolean allPositive = vals.stream().allMatch(v -> v != null && v > 0);
                        if (ratioAsked && rows.size() >= 2 && rows.size() <= 8 && allPositive) {
                            chart = new ChartSpec("pie", cols.get(catIdx).name(), x,
                                    List.of(new ChartSpec.Series(cols.get(vIdx).name(), vals)), "问题关心构成且类别≤8,饼图直观");
                        } else {
                            double avgLen = x.stream().mapToInt(String::length).average().orElse(0);
                            List<ChartSpec.Series> series = new ArrayList<>();
                            for (int k = 0; k < Math.min(numIdx.size(), 3); k++) {
                                int idx = numIdx.get(k);
                                series.add(new ChartSpec.Series(cols.get(idx).name(),
                                        rows.stream().map(rw -> toD.apply(new ArrayList<>(rw.values()).get(idx))).toList()));
                            }
                            chart = new ChartSpec(rows.size() > 12 || avgLen > 6 ? "barH" : "bar", cols.get(catIdx).name(), x, series,
                                    "类别型对比," + (rows.size() > 12 || avgLen > 6 ? "标签较长采用横向条形图" : "采用柱状图"));
                        }
                    } else {
                        chart = new ChartSpec("none", null, null, null, "结果形态复杂,以表格呈现");
                    }
                }

                payload.setSql(cand.getSql());
                payload.setExplanation(cand.getExplanation());
                payload.setColumns(r.columns());
                payload.setRows(r.rows());
                payload.setRowCount(r.rowCount());
                payload.setChart(chart);
                payload.setTrace(stored.trace());

                // ---------- 第 5 步:写语义缓存(只缓存成功结果;容量超限时全清,演示版粗粒度) ----------
                if (semanticCache.size() >= props.getCache().getMaxSize()) {
                    semanticCache.clear();
                }
                semanticCache.put(stored.cacheKey(), new CacheEntry(payload, System.currentTimeMillis()));
            } catch (Exception e) {
                log.warn("SQL 执行失败: {}", e.getMessage());
                // 两段式里执行阶段 LLM 不在场,无法自修复,直接优雅降级
                payload = new AnswerPayload();
                payload.setFallback(true);
                payload.setFallbackHint("查询执行失败:" + e.getMessage() + "。可以换个问法,或重新 parse 再试");
                payload.setEngine(cand.getEngine());
            }
        }

        // ---------- 第 6 步:持久化助手消息 + query_log 审计(闲聊只落消息,不进审计表) ----------
        payload.setTookMs(System.currentTimeMillis() - t0);
        int costMs = (int) payload.getTookMs();
        String content = payload.isFallback() ? payload.getFallbackHint()
                : (payload.getExplanation() == null || payload.getExplanation().isBlank()
                ? "已完成查询,共 " + payload.getRowCount() + " 行结果" : payload.getExplanation());
        ChatMessage botMsg = new ChatMessage();
        botMsg.setSessionId(stored.sessionId());
        botMsg.setRole("ASSISTANT");
        botMsg.setContent(content);
        try {
            botMsg.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            botMsg.setPayload("{}");
        }
        messageMapper.insert(botMsg);
        if (payload.getSql() != null) {
            QueryLog queryLog = new QueryLog();
            queryLog.setUserId(stored.userId());
            queryLog.setDatasetId(stored.datasetId());
            queryLog.setQuestion(stored.question());
            queryLog.setFinalSql(payload.getSql());
            queryLog.setEngine(payload.getEngine());
            queryLog.setStatus(payload.isFallback() ? "FAILED" : "SUCCESS");
            queryLog.setRowCount(payload.getRowCount());
            queryLog.setCostMs(costMs);
            queryLog.setCacheHit(payload.isCacheHit() ? 1 : 0);
            String err = payload.isFallback() ? content : null;
            queryLog.setErrorMsg(err != null && err.length() > 900 ? err.substring(0, 900) + "…(截断)" : err);
            queryLogMapper.insert(queryLog);
        }
        return payload;
    }
}
