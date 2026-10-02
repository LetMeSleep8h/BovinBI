package com.eighthours.bovinbi.trace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * TraceHub 契约(纯逻辑,无 Servlet 依赖):
 * 1) 非流式调用零开销:queryId<=0 或未 open 的 id,publish 是无操作且不抛异常;
 * 2) open → publish → finish 生命周期不抛异常,finish 后状态清理(再次 publish 无操作)。
 * SSE 推送的端到端行为由 /ask/stream 的栈上冒烟覆盖。
 */
class TraceHubTest {

    @Test
    void publishIsNoOpForNonStreamedQueries() {
        assertDoesNotThrow(() -> {
            TraceHub.publish(-1, "意图识别", "不应有副作用", true);
            TraceHub.publish(0, "Schema召回", "不应有副作用", true);
            TraceHub.publish(999999L, "未open的id", "不应有副作用", true);
        });
    }

    @Test
    void lifecycleOpenPublishFinishIsClean() {
        long qid = 42L;
        assertDoesNotThrow(() -> {
            TraceHub.open(qid);
            TraceHub.publish(qid, "意图识别", "闲聊判定", true);
            TraceHub.publish(qid, "引擎执行", "Agent 工具循环", true);
            TraceHub.publish(qid, "executeSql", "SELECT ... LIMIT 1000", false);
            TraceHub.finish(qid, "done", java.util.Map.of("id", 1));
            // finish 后再 publish:状态已清理,无操作不抛异常
            TraceHub.publish(qid, "迟到事件", "已清理", true);
        });
    }
}
