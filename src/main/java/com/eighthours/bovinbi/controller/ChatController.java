package com.eighthours.bovinbi.controller;

import com.eighthours.bovinbi.common.ApiResponse;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.ChatReq;
import com.eighthours.bovinbi.dto.MessageVO;
import com.eighthours.bovinbi.dto.SessionVO;
import com.eighthours.bovinbi.request.ChatExecuteReq;
import com.eighthours.bovinbi.request.ChatParseReq;
import com.eighthours.bovinbi.response.ChatParseResp;
import com.eighthours.bovinbi.service.ChatQueryService;
import com.eighthours.bovinbi.service.ChatService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatService chatService;
    private final ChatQueryService chatQueryService;
    private final com.eighthours.bovinbi.mcp.plan.PlanExecutor planExecutor;

    @GetMapping("/sessions")
    public ApiResponse<List<SessionVO>> sessions() {
        return ApiResponse.ok(chatService.listSessions());
    }

    @PostMapping("/sessions")
    public ApiResponse<Map<String, Long>> create(@RequestBody Map<String, Long> body) {
        Long datasetId = body.get("datasetId");
        return ApiResponse.ok(Map.of("sessionId", chatService.createSession(datasetId)));
    }

    @DeleteMapping("/sessions/{id}")
    public ApiResponse<Void> delete(@PathVariable Long id) {
        chatService.deleteSession(id);
        return ApiResponse.ok();
    }

    @GetMapping("/sessions/{id}/messages")
    public ApiResponse<List<MessageVO>> messages(@PathVariable Long id) {
        return ApiResponse.ok(chatService.messages(id));
    }

    @PostMapping("/ask")
    public ApiResponse<MessageVO> ask(@Valid @RequestBody ChatReq req) {
        return ApiResponse.ok(chatService.ask(req.sessionId(), req.question(), req.engine(), req.model()));
    }

    /**
     * 流式问答(SSE):实时推送 AI 工作内容(意图识别/Schema召回/工具调用/执行…),
     * 最后以 done 事件携带完整助手消息。前端用 fetch 解析 event stream(带鉴权头)。
     */
    @PostMapping(value = "/ask/stream", produces = org.springframework.http.MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter askStream(@Valid @RequestBody ChatReq req) {
        long queryId = System.nanoTime();
        com.eighthours.bovinbi.trace.TraceHub.open(queryId);
        org.springframework.web.servlet.mvc.method.annotation.SseEmitter emitter =
                com.eighthours.bovinbi.trace.TraceHub.subscribe(queryId);
        // 身份是 ThreadLocal:捕获请求线程的 uid/角色,带进异步执行线程(会话校验/审计都依赖它)
        final Long uid = com.eighthours.bovinbi.security.UserContext.uid();
        final String uname = com.eighthours.bovinbi.security.UserContext.username();
        final String role = com.eighthours.bovinbi.security.UserContext.role();
        java.util.concurrent.CompletableFuture.runAsync(() -> {
            com.eighthours.bovinbi.security.UserContext.set(uid, uname, role);
            try {
                MessageVO bot = chatService.askStream(req.sessionId(), req.question(), queryId, req.engine(), req.model());
                com.eighthours.bovinbi.trace.TraceHub.finish(queryId, "done", bot);
            } catch (Exception e) {
                com.eighthours.bovinbi.trace.TraceHub.finish(queryId, "error",
                        java.util.Map.of("message", e.getMessage() == null ? "查询失败" : e.getMessage()));
            } finally {
                com.eighthours.bovinbi.security.UserContext.clear();
            }
        });
        return emitter;
    }

    /** 逐步确认模式:回填用户的执行/取消决策(挂起中的流式问答由此放行或终止) */
    @PostMapping("/approve/{queryId}")
    public ApiResponse<Void> approve(@PathVariable long queryId, @RequestBody ApproveReq req) {
        com.eighthours.bovinbi.security.ApprovalHub.offer(queryId, Boolean.TRUE.equals(req.approve()));
        return ApiResponse.ok();
    }

    public record ApproveReq(Boolean approve) {
    }

    /**
     * 多步计划(融合 plan/verify/拓扑执行骨架):只规划不执行 ——
     * 返回四道校验后的拓扑序步骤,前端可展示确认;确认后调 /plan/execute。
     */
    @PostMapping("/plan")
    public ApiResponse<List<Map<String, Object>>> plan(@Valid @RequestBody ChatReq req) {
        return ApiResponse.ok(planExecutor.plan(req.datasetId(), req.question(), req.sessionId()).stream()
                .map(st -> Map.of("id", (Object) st.id(), "tool", st.tool(),
                        "params", st.params(), "depends_on", st.dependsOn()))
                .toList());
    }

    /** 多步计划:按拓扑序执行已确认的计划(每步过注册中心:守护/配额/轨迹同一套) */
    @PostMapping("/plan/execute")
    public ApiResponse<AnswerPayload> executePlan(@Valid @RequestBody PlanExecuteReq req) {
        return ApiResponse.ok(planExecutor.execute(req.datasetId(), req.question(), req.sessionId(),
                System.nanoTime(), req.steps()));
    }

    public record PlanExecuteReq(Long datasetId, String question, Long sessionId,
                                 List<com.eighthours.bovinbi.mcp.plan.PlanOps.Step> steps) {
    }

    /** 两段式:理解问题并生成/守护 SQL,不查库 */
    @PostMapping("/parse")
    public ApiResponse<ChatParseResp> parse(@RequestBody ChatParseReq req) {
        return ApiResponse.ok(chatQueryService.parse(req));
    }

    /** 两段式:按 queryId + parseId 取回解析结果并查库出数据 */
    @PostMapping("/execute")
    public ApiResponse<AnswerPayload> execute(@RequestBody ChatExecuteReq req) {
        return ApiResponse.ok(chatQueryService.execute(req));
    }
}
