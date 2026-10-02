package com.eighthours.bovinbi.security;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 执行审批中枢(逐步确认模式的等待/放行机制):
 * - await:STEP 流程生成 SQL 后登记等待,挂起至用户决策或超时;
 * - offer:审批端点回填决策(true=放行执行 / false=取消);
 * - remove:流程结束清理,防泄漏。单机内存态;多实例部署应换 Redis,接口不变。
 */
public final class ApprovalHub {

    private static final Map<Long, CompletableFuture<Boolean>> PENDING = new ConcurrentHashMap<>();

    private ApprovalHub() {
    }

    /** 登记并返回等待句柄(同一 queryId 重复登记返回已存在的) */
    public static CompletableFuture<Boolean> await(long queryId) {
        return PENDING.computeIfAbsent(queryId, k -> new CompletableFuture<>());
    }

    /** 回填决策;无等待者时为无操作 */
    public static void offer(long queryId, boolean approved) {
        CompletableFuture<Boolean> f = PENDING.get(queryId);
        if (f != null) {
            f.complete(approved);
        }
    }

    public static void remove(long queryId) {
        PENDING.remove(queryId);
    }
}
