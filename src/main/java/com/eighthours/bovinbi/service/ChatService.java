package com.eighthours.bovinbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.MessageVO;
import com.eighthours.bovinbi.dto.SessionVO;
import com.eighthours.bovinbi.entity.ChatMessage;
import com.eighthours.bovinbi.entity.ChatSession;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.entity.QueryLog;
import com.eighthours.bovinbi.entity.User;
import com.eighthours.bovinbi.mapper.ChatMessageMapper;
import com.eighthours.bovinbi.mapper.ChatSessionMapper;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.mapper.QueryLogMapper;
import com.eighthours.bovinbi.mapper.UserMapper;
import com.eighthours.bovinbi.request.ChatExecuteReq;
import com.eighthours.bovinbi.request.ChatParseReq;
import com.eighthours.bovinbi.response.ChatParseResp;
import com.eighthours.bovinbi.security.ApprovalHub;
import com.eighthours.bovinbi.security.UserContext;
import com.eighthours.bovinbi.trace.TraceHub;
import com.eighthours.bovinbi.llm.ModelContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/** 会话与问答编排:持久化用户/助手消息、审计日志 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final DatasetMapper datasetMapper;
    private final QueryLogMapper queryLogMapper;
    private final Nl2SqlService nl2SqlService;
    private final com.eighthours.bovinbi.llm.LlmClient llmClient;
    private final PythonAgentService pythonAgentService;
    private final ChartAdvisor chartAdvisor;
    private final ChatQueryService chatQueryService;
    private final UserMapper userMapper;
    private final ObjectMapper objectMapper;
    private final org.springframework.beans.factory.ObjectProvider<com.eighthours.bovinbi.service.rag1.Rag1IntentService> rag1Intent;

    /** 逐步确认模式的等待上限:超时视为拒绝(不无限挂起连接与线程) */
    private static final long APPROVAL_TIMEOUT_MS = 120_000;

    public List<SessionVO> listSessions() {
        List<ChatSession> sessions = sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
                .eq(ChatSession::getUserId, UserContext.uid())
                .orderByDesc(ChatSession::getId));
        return sessions.stream().map(s -> {
            Dataset ds = datasetMapper.selectById(s.getDatasetId());
            ChatMessage last = messageMapper.selectOne(new LambdaQueryWrapper<ChatMessage>()
                    .eq(ChatMessage::getSessionId, s.getId())
                    .orderByDesc(ChatMessage::getId)
                    .last("LIMIT 1"));
            return new SessionVO(s.getId(), s.getDatasetId(), ds == null ? "" : ds.getName(),
                    s.getTitle(), s.getCreatedAt(), last == null ? s.getCreatedAt() : last.getCreatedAt());
        }).toList();
    }

    public Long createSession(Long datasetId) {
        // 数据集前置校验:快速失败,避免脏 datasetId 存入会话、拖到 parse/ask 阶段才报难懂的错
        if (datasetId == null || datasetMapper.selectById(datasetId) == null) {
            throw new BizException(400, "数据集不存在,请从数据集列表中选择后创建会话");
        }
        ChatSession s = new ChatSession();
        s.setUserId(UserContext.uid());
        s.setDatasetId(datasetId);
        sessionMapper.insert(s);
        return s.getId();
    }

    public void deleteSession(Long sessionId) {
        ChatSession s = mustOwn(sessionId);
        sessionMapper.deleteById(s.getId());
        messageMapper.delete(new LambdaQueryWrapper<ChatMessage>().eq(ChatMessage::getSessionId, sessionId));
    }

    public List<MessageVO> messages(Long sessionId) {
        mustOwn(sessionId);
        return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
                        .eq(ChatMessage::getSessionId, sessionId)
                        .orderByAsc(ChatMessage::getId))
                .stream().map(this::toVO).toList();
    }

    /** 核心问答:持久化两条消息 + 审计日志,返回助手消息 */
    public MessageVO ask(Long sessionId, String question) {
        return runAsk(sessionId, question, -1L, null);
    }

    public MessageVO ask(Long sessionId, String question, String engine) {
        return ask(sessionId, question, engine, null);
    }

    public MessageVO ask(Long sessionId, String question, String engine, String model) {
        try {
            ModelContext.set(model);
            return runAsk(sessionId, question, -1L, engine);
        } finally {
            ModelContext.clear();
        }
    }

    /** 流式问答:阶段经 TraceHub 实时推送(见 ChatController /ask/stream),最终消息随 done 事件返回 */
    public MessageVO askStream(Long sessionId, String question, long queryId, String engine, String model) {
        try {
            ModelContext.set(model);
            return runAsk(sessionId, question, queryId, engine);
        } finally {
            ModelContext.clear();
        }
    }

    /**
     * Python 引擎链路:转发给 python-agent 服务(其工具调用经 MCP 回环到底座),
     * 步骤轨迹推 TraceHub 实时上屏;不可用时降级 Java 引擎,标签注明降级。
     */
    private AnswerPayload answerByPython(ChatSession session, String question, long queryId) {
        TraceHub.publish(queryId, "引擎执行", "Python Agent(跨语言,MCP 回环执行)", true);
        try {
            AnswerPayload p = pythonAgentService.answer(session.getDatasetId(), question,
                    session.getId(), ModelContext.get());
            // Python 侧步骤在响应里带回:推上实时流(准实时,完整步骤随载荷返回)
            if (p.getTrace() != null) {
                p.getTrace().forEach(t -> TraceHub.publish(queryId,
                        "py:" + t.tool(), t.args(), t.ok()));
            }
            if (p.getColumns() != null && !p.getColumns().isEmpty()) {
                p.setChart(chartAdvisor.advise(question, p.getColumns(), p.getRows()));
            }
            return p;
        } catch (Exception e) {
            log.warn("Python 引擎失败,降级 Java 引擎: {}", e.getMessage());
            TraceHub.publish(queryId, "Python降级", "Python Agent 不可用,自动切回 Java 引擎", false);
            AnswerPayload p = nl2SqlService.answer(session.getDatasetId(), question, session.getId(), queryId);
            p.setEngine("PYTHON(降级JAVA)");
            return p;
        }
    }

    /**
     * 逐步确认模式流程:parse 生成 SQL(不执行)→ SSE 推 approval 事件(带 SQL)
     * → 挂起等待用户决策(/api/chat/approve)→ 放行则 execute(approved)→ 组装消息。
     * 拒绝/超时:落一条取消说明消息,不执行任何查询。
     */
    private MessageVO runAskStepMode(ChatSession session, String question, long queryId) {
        TraceHub.publish(queryId, "权限模式", "逐步确认:先生成 SQL,等待用户确认后执行", true);
        try {
            ChatParseResp parse = chatQueryService.parse(ChatParseReq.builder()
                    .queryId(queryId).sessionId(session.getId()).question(question).build());
            if (!"COMPLETED".equalsIgnoreCase(String.valueOf(parse.getState()))) {
                return finishStep(session, queryId, parse.getErrorMsg() == null ? "未能理解该问题" : parse.getErrorMsg(), null);
            }
            var cand = parse.getCandidates().get(0);
            TraceHub.publish(queryId, "SQL待确认", cand.getExplanation() == null ? "" : cand.getExplanation(), true);
            TraceHub.sendEvent(queryId, "approval", Map.of(
                    "queryId", queryId,
                    "sql", cand.getSql() == null ? "" : cand.getSql(),
                    "explanation", cand.getExplanation() == null ? "" : cand.getExplanation()));
            boolean approved = ApprovalHub.await(queryId).get(APPROVAL_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS);
            ApprovalHub.remove(queryId);
            TraceHub.publish(queryId, approved ? "用户已确认" : "用户已取消", approved ? "放行执行" : "本次不执行任何查询", approved);
            if (!approved) {
                return finishStep(session, queryId, "已按「逐步确认」模式取消执行,未运行任何 SQL。", null);
            }
            AnswerPayload payload = chatQueryService.execute(ChatExecuteReq.builder()
                    .queryId(queryId).parseId(cand.getParseId()).sessionId(session.getId()).approved(true).build());
            return finishStep(session, queryId, contentOf(payload), payload);
        } catch (Exception e) {
            log.warn("逐步确认流程异常: {}", e.getMessage());
            ApprovalHub.remove(queryId);
            return finishStep(session, queryId, "查询失败: " + e.getMessage(), null);
        }
    }

    /** STEP/闲聊流程收尾:助手消息落库(成功路径 execute 已落,此处补对话/取消/失败消息)并组装 VO */
    private MessageVO finishStep(ChatSession session, long queryId, String content, AnswerPayload payload) {
        if (payload == null) {
            ChatMessage bot = new ChatMessage();
            bot.setSessionId(session.getId());
            bot.setRole("ASSISTANT");
            bot.setContent(content);
            try {
                // 正常对话载荷(fallback=false):AnswerCard 以解释文本渲染,
                // 不再触发"暂时无法回答"警告框
                bot.setPayload(objectMapper.writeValueAsString(Map.of(
                        "engine", "CHAT", "explanation", content,
                        "columns", List.of(), "rows", List.of(), "rowCount", 0, "fallback", false)));
            } catch (Exception ignore) {
                bot.setPayload("{}");
            }
            messageMapper.insert(bot);
            return toVO(bot);
        }
        // execute 已落库完整消息;这里返回等价 VO 供前端即时渲染
        try {
            return new MessageVO(null, session.getId(), "ASSISTANT", content,
                    objectMapper.readTree(objectMapper.writeValueAsString(payload)), null);
        } catch (Exception ignore) {
            return new MessageVO(null, session.getId(), "ASSISTANT", content, null, null);
        }
    }

    /** 对话 AI 系统提示词:像朋友一样聊天,顺势引导到数据提问 */
    private static final String SMALL_TALK_SYSTEM = """
            你是 BovinBI 里的数据助手,用户此刻在和你闲聊。要求:
            - 像朋友一样自然回应:可以共情、可以幽默,每轮 2~3 句话以内,口语化,别堆客套话、别用列表;
            - 你的绝活是把一句话变成 SQL 并出图表(电商零售/牧场养殖/全球牛奶产量三个数据集,趋势、TopN、占比都行);
            - 察觉用户其实想查数据时,自然地给一个能直接问的例子(如"销售额Top10商品类目");
            - 不编造自己没有的能力,不聊与工作无关的敏感话题。""";

    /**
     * 闲聊回答(三角色 · 角色二):python 引擎转发 Python Agent(其闲聊节点同为 LLM 对话),
     * 其余走本服务 LLM;两者失败或离线时回落固定文案 —— 对外永远是"有回应",绝不静默。
     */
    private String smallTalk(ChatSession session, String question, String engine) {
        if ("python".equalsIgnoreCase(engine)) {
            try {
                AnswerPayload p = pythonAgentService.answer(session.getDatasetId(), question,
                    session.getId(), ModelContext.get());
                if (!p.isFallback() && p.getExplanation() != null && !p.getExplanation().isBlank()) {
                    return p.getExplanation();
                }
            } catch (Exception e) {
                log.warn("Python 闲聊失败,回落 Java 对话 AI: {}", e.getMessage());
            }
        }
        try {
            String reply = llmClient.chat(SMALL_TALK_SYSTEM, question);
            return reply == null || reply.isBlank() ? chitChatAnswer(question) : reply.trim();
        } catch (Exception e) {
            log.warn("对话 AI 失败(离线/超时),回落固定文案: {}", e.getMessage());
            return chitChatAnswer(question);
        }
    }

    /** 闲聊判定:rag1 向量识别优先(无数据信号词才可判),关键词版兜底 */
    private boolean isChitChat(String question) {
        var rag1 = rag1Intent.getIfAvailable();
        if (rag1 != null && rag1.recognize(question).chitChat()) {
            return true;
        }
        return new com.eighthours.bovinbi.service.ChitChatHandler().isChitChat(question);
    }

    /** 闲聊回复:能力介绍随当前数据集语境,顺带展示 Python/Java 双引擎 */
    private String chitChatAnswer(String question) {
        if (question.contains("谢谢") || question.contains("多谢") || question.contains("感谢")) {
            return "不客气!还想看什么数据,直接问就行。";
        }
        if (question.contains("再见") || question.contains("拜拜")) {
            return "再见!数据随时在这里等你。";
        }
        return "我是 BovinBI 数据分析助手,能把你的一句自然语言变成 SQL 并出图表:"
                + "趋势 / TopN 排行 / 占比构成 / 分组统计 / 单值指标都行。"
                + "上方可以切换 ☕Java 引擎 与 🐍Python Agent 两种 AI 引擎;"
                + "「查询历史」页还能看你的每日 token 用量。"
                + "试试:销售额Top10商品类目 / 各客户州销售额 / 每月销售额趋势。";
    }

    private String contentOf(AnswerPayload p) {
        if (p.isFallback()) {
            return p.getFallbackHint() == null ? "查询失败" : p.getFallbackHint();
        }
        return p.getExplanation() == null || p.getExplanation().isBlank()
                ? "已完成查询,共 " + p.getRowCount() + " 行结果" : p.getExplanation();
    }

    private MessageVO runAsk(Long sessionId, String question, long queryId, String engine) {
        TraceHub.publish(queryId, "会话校验", "校验会话归属与数据集", true);
        ChatSession session = mustOwn(sessionId);
        // 意图分流前置(引擎无关):rag1 优先,关键词兜底 —— 闲聊/能力询问两个引擎都不该进 SQL 链路。
        // 此前只在 Nl2SqlService(java)里有,Python 链路绕过了它,"你能干什么"被硬编成 SQL 而失败
        if (isChitChat(question)) {
            // 三角色架构 · 角色二(闲聊 AI):判别为闲聊后交给对话模型自由回答,
            // 不再回写死文案 —— engine=python 走 Python Agent 的闲聊节点,否则走本服务 LLM
            TraceHub.publish(queryId, "闲聊对话", "非取数问题,交给对话 AI 回答", true);
            return finishStep(session, queryId, smallTalk(session, question, engine), null);
        }
        // 权限划分:STEP(每一步过问)→ 生成 SQL 后等用户确认再执行;AUTO(完全允许)→ 全自动
        User owner = userMapper.selectById(session.getUserId());
        if (queryId > 0 && owner != null && "STEP".equalsIgnoreCase(owner.getApprovalMode())) {
            return runAskStepMode(session, question, queryId);
        }
        if (session.getTitle() == null || session.getTitle().isBlank()) {
            ChatSession upd = new ChatSession();
            upd.setId(sessionId);
            upd.setTitle(question.length() > 20 ? question.substring(0, 20) : question);
            sessionMapper.updateById(upd);
        }

        ChatMessage userMsg = new ChatMessage();
        userMsg.setSessionId(sessionId);
        userMsg.setRole("USER");
        userMsg.setContent(question);
        messageMapper.insert(userMsg);

        long t0 = System.currentTimeMillis();
        String payloadEngine = null;
        String finalSql = null;
        TraceHub.publish(queryId, "记录问题", "用户消息落库", true);
        QueryLog queryLog = new QueryLog();
        queryLog.setUserId(UserContext.uid());
        queryLog.setDatasetId(session.getDatasetId());
        queryLog.setQuestion(question);

        AnswerPayload payload;
        try {
            if ("python".equalsIgnoreCase(engine)) {
                payload = answerByPython(session, question, queryId);
            } else {
                payload = nl2SqlService.answer(session.getDatasetId(), question, sessionId, queryId);
            }
            payloadEngine = payload.getEngine();
            finalSql = payload.getSql();
        } catch (Exception e) {
            log.error("问答失败: {}", e.getMessage());
            payload = new AnswerPayload();
            payload.setFallback(true);
            payload.setFallbackHint("查询失败:" + e.getMessage());
            payloadEngine = "FAILED";
        }

        TraceHub.publish(queryId, "查询执行", "引擎: " + (payloadEngine == null ? "-" : payloadEngine),
                !payload.isFallback());
        String content = payload.isFallback()
                ? payload.getFallbackHint()
                : (payload.getExplanation() == null || payload.getExplanation().isBlank()
                        ? "已完成查询,共 " + payload.getRowCount() + " 行结果"
                        : payload.getExplanation());

        ChatMessage botMsg = new ChatMessage();
        botMsg.setSessionId(sessionId);
        botMsg.setRole("ASSISTANT");
        botMsg.setContent(content);
        try {
            botMsg.setPayload(objectMapper.writeValueAsString(payload));
        } catch (Exception e) {
            botMsg.setPayload("{}");
        }
        messageMapper.insert(botMsg);

        queryLog.setFinalSql(finalSql);
        queryLog.setEngine(payloadEngine);
        queryLog.setStatus(payload.isFallback() ? "FAILED" : "SUCCESS");
        queryLog.setRowCount(payload.getRowCount());
        queryLog.setCostMs((int) (System.currentTimeMillis() - t0));
        queryLog.setCacheHit(payload.isCacheHit() ? 1 : 0);
        queryLog.setErrorMsg(payload.isFallback() ? truncate(content, 900) : null);
        queryLogMapper.insert(queryLog);

        return toVO(botMsg);
    }

    private String truncate(String s, int max) {
        if (s == null || s.length() <= max) return s;
        return s.substring(0, max) + "…(截断)";
    }

    private ChatSession mustOwn(Long sessionId) {
        ChatSession s = sessionMapper.selectById(sessionId);
        if (s == null || !s.getUserId().equals(UserContext.uid())) {
            throw new BizException(404, "会话不存在");
        }
        return s;
    }

    private MessageVO toVO(ChatMessage m) {
        JsonNode payload = null;
        if (m.getPayload() != null) {
            try {
                payload = objectMapper.readTree(m.getPayload());
            } catch (Exception ignore) {
            }
        }
        return new MessageVO(m.getId(), m.getSessionId(), m.getRole(), m.getContent(), payload, m.getCreatedAt());
    }
}
