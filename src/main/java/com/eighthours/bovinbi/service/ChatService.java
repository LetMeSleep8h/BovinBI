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
import com.eighthours.bovinbi.mapper.ChatMessageMapper;
import com.eighthours.bovinbi.mapper.ChatSessionMapper;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.mapper.QueryLogMapper;
import com.eighthours.bovinbi.security.UserContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

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
    private final ObjectMapper objectMapper;

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
        ChatSession session = mustOwn(sessionId);
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
        String engine = null;
        String finalSql = null;
        QueryLog queryLog = new QueryLog();
        queryLog.setUserId(UserContext.uid());
        queryLog.setDatasetId(session.getDatasetId());
        queryLog.setQuestion(question);

        AnswerPayload payload;
        try {
            payload = nl2SqlService.answer(session.getDatasetId(), question, sessionId);
            engine = payload.getEngine();
            finalSql = payload.getSql();
        } catch (Exception e) {
            log.error("问答失败: {}", e.getMessage());
            payload = new AnswerPayload();
            payload.setFallback(true);
            payload.setFallbackHint("查询失败:" + e.getMessage());
            engine = "FAILED";
        }

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
        queryLog.setEngine(engine);
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
