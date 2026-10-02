package com.eighthours.bovinbi.trace;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实时工作流事件枢纽(SSE):把"AI 正在做什么"逐条推给前端。
 * - 静态工具形态:链路深处(AgentRunContext/Nl2SqlService 等)无 Spring 依赖即可发布;
 * - 事件先入历史再推订阅者:订阅晚于事件开始时(前端建立连接的竞态)可回放已发生步骤;
 * - queryId 生命周期:open → publish* → finish;finish 后清理,防内存泄漏;
 *   挂起的订阅者超时由 SseEmitter 自身超时兜底。
 * 演示为单机内存态;多实例部署应换 Redis Pub/Sub,发布接口不变。
 */
@Slf4j
public final class TraceHub {

    /** 一步工作事件:name=阶段/工具名, detail=一句话说明, ok=成败(进行中为 true) */
    public record StepEvent(long queryId, int seq, String name, String detail, boolean ok, long ts) {
    }

    private static final Map<Long, List<StepEvent>> HISTORY = new ConcurrentHashMap<>();
    private static final Map<Long, List<SseEmitter>> SUBSCRIBERS = new ConcurrentHashMap<>();
    private static final Map<Long, AtomicInteger> SEQS = new ConcurrentHashMap<>();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TraceHub() {
    }

    /** 开启一次查询的实时流(queryId 由入口生成) */
    public static void open(long queryId) {
        HISTORY.put(queryId, new ArrayList<>());
        SEQS.put(queryId, new AtomicInteger());
    }

    /** 发布一步;queryId 未 open 时为无操作(非流式调用零开销) */
    public static void publish(long queryId, String name, String detail, boolean ok) {
        if (queryId <= 0) {
            return;
        }
        AtomicInteger seq = SEQS.get(queryId);
        List<StepEvent> history = HISTORY.get(queryId);
        if (seq == null || history == null) {
            return;
        }
        StepEvent e = new StepEvent(queryId, seq.incrementAndGet(), name, detail, ok, System.currentTimeMillis());
        synchronized (history) {
            history.add(e);
        }
        push(e);
    }

    /** 订阅:先回放历史步骤,再接续实时推送 */
    public static SseEmitter subscribe(long queryId) {
        SseEmitter emitter = new SseEmitter(180_000L);
        List<SseEmitter> subs = SUBSCRIBERS.computeIfAbsent(queryId, k -> new ArrayList<>());
        synchronized (subs) {
            subs.add(emitter);
        }
        emitter.onCompletion(() -> remove(queryId, emitter));
        emitter.onTimeout(() -> remove(queryId, emitter));
        List<StepEvent> history = HISTORY.get(queryId);
        if (history != null) {
            synchronized (history) {
                for (StepEvent e : history) {
                    send(emitter, e);
                }
            }
        }
        return emitter;
    }

    /** 结束:推终止事件并清理本次查询的全部状态 */
    public static void finish(long queryId, String type, Object payload) {
        sendEvent(queryId, type, payload);
        List<SseEmitter> subs = SUBSCRIBERS.get(queryId);
        if (subs != null) {
            synchronized (subs) {
                for (SseEmitter emitter : new ArrayList<>(subs)) {
                    try {
                        emitter.complete();
                    } catch (Exception ignore) {
                        // 订阅端已断开,清理即可
                    }
                }
            }
        }
        HISTORY.remove(queryId);
        SEQS.remove(queryId);
        SUBSCRIBERS.remove(queryId);
    }

    /** 推送自定义事件(不结束流):逐步确认模式的 approval 事件等交互类通知用 */
    public static void sendEvent(long queryId, String eventName, Object payload) {
        List<SseEmitter> subs = SUBSCRIBERS.get(queryId);
        if (subs == null) {
            return;
        }
        synchronized (subs) {
            for (SseEmitter emitter : new ArrayList<>(subs)) {
                try {
                    emitter.send(SseEmitter.event().name(eventName).data(MAPPER.writeValueAsString(payload)));
                } catch (Exception ignore) {
                    // 订阅端已断开
                }
            }
        }
    }

    private static void push(StepEvent e) {
        List<SseEmitter> subs = SUBSCRIBERS.get(e.queryId());
        if (subs == null) {
            return;
        }
        synchronized (subs) {
            for (SseEmitter emitter : new ArrayList<>(subs)) {
                send(emitter, e);
            }
        }
    }

    private static void send(SseEmitter emitter, StepEvent e) {
        try {
            emitter.send(SseEmitter.event().name("step").data(MAPPER.writeValueAsString(e)));
        } catch (IOException | IllegalStateException ex) {
            log.debug("SSE 推送失败(订阅端断开): {}", ex.getMessage());
        }
    }

    private static void remove(long queryId, SseEmitter emitter) {
        List<SseEmitter> subs = SUBSCRIBERS.get(queryId);
        if (subs != null) {
            synchronized (subs) {
                subs.remove(emitter);
            }
        }
    }
}
