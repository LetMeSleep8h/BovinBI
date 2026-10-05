package com.eighthours.bovinbi.mcp.plan;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 多步计划:LLM 输出结构化 JSON 计划(每步带依赖),服务端四道校验后按拓扑序执行。
 * 校验四道关卡(学自 ai-picture-editor plan.py,Java 重写):
 *   ① 步数预算(MAX_STEPS,防提示词注入撑爆循环)
 *   ② 工具存在性 + 写操作拦截(mutator 不进自动计划)
 *   ③ 依赖补全(缺省链到前一步;依赖不存在的步骤 id → 拒)
 *   ④ 环检测(Kahn 拓扑排序:入度表 + BFS,访问数≠节点数 → 成环 → 拒)
 * 差异化:②里加了参考实现没有的写操作拦截与 SQL 预校验挂点(守护在执行层仍兜底)。
 */
public final class PlanOps {

    public static final int MAX_STEPS = 8;

    public record Step(String id, String tool, Map<String, Object> params, List<String> dependsOn) {
    }

    public static class PlanException extends RuntimeException {
        public PlanException(String message) {
            super(message);
        }
    }

    /** 已注册工具名集合(存在性校验) + 写操作工具集合(自动计划拦截) */
    private final Set<String> knownTools;
    private final Set<String> mutatorTools;

    public PlanOps(Set<String> knownTools, Set<String> mutatorTools) {
        this.knownTools = Set.copyOf(knownTools);
        this.mutatorTools = Set.copyOf(mutatorTools);
    }

    /**
     * 补全 + 四道校验,返回拓扑序步骤(依赖在前)。
     * 校验失败抛 PlanException(消息面向用户,可直接回给前端)。
     */
    public List<Step> assemble(List<Map<String, Object>> raw) {
        if (raw == null || raw.isEmpty()) {
            throw new PlanException("计划为空");
        }
        if (raw.size() > MAX_STEPS) {
            throw new PlanException("计划超过 " + MAX_STEPS + " 步,请拆成多次说明");
        }

        // ① 补全:id 缺省 s{n};依赖缺省链到前一步(顺序执行的天然语义)
        List<Step> steps = new ArrayList<>();
        String prev = null;
        int index = 1;
        for (Map<String, Object> item : raw) {
            String tool = str(item.get("tool"));
            if (tool == null || tool.isBlank()) {
                throw new PlanException("步骤 " + index + " 缺少 tool 字段");
            }
            String id = item.get("id") != null && !str(item.get("id")).isBlank()
                    ? str(item.get("id")) : "s" + index;
            @SuppressWarnings("unchecked")
            List<String> depends = item.get("depends_on") != null
                    ? (List<String>) item.get("depends_on")
                    : (prev != null ? List.of(prev) : List.of());
            @SuppressWarnings("unchecked")
            Map<String, Object> params = item.get("params") != null
                    ? (Map<String, Object>) item.get("params") : Map.of();
            steps.add(new Step(id, tool, params, depends));
            prev = id;
            index++;
        }

        // ② 工具存在性 + 写操作拦截
        for (Step s : steps) {
            if (!knownTools.contains(s.tool())) {
                throw new PlanException("未注册的工具: " + s.tool());
            }
            if (mutatorTools.contains(s.tool())) {
                throw new PlanException("写操作工具 " + s.tool() + " 不允许出现在自动计划中(请单独确认执行)");
            }
        }

        // ③ 依赖存在性 + 自依赖
        Map<String, Step> byId = new LinkedHashMap<>();
        steps.forEach(s -> byId.put(s.id(), s));
        if (byId.size() != steps.size()) {
            throw new PlanException("步骤 id 重复");
        }
        for (Step s : steps) {
            for (String dep : s.dependsOn()) {
                if (!byId.containsKey(dep)) {
                    throw new PlanException("步骤 " + s.id() + " 依赖了不存在的步骤 " + dep);
                }
                if (dep.equals(s.id())) {
                    throw new PlanException("步骤 " + s.id() + " 依赖了自身");
                }
            }
        }

        // ④ 环检测(Kahn)+ 拓扑序输出
        return topoSort(steps, byId);
    }

    /** Kahn 算法:入度表 + 队列;输出数 < 节点数 → 存在环。手写实现即学习产出② */
    private List<Step> topoSort(List<Step> steps, Map<String, Step> byId) {
        Map<String, Integer> incoming = new LinkedHashMap<>();
        Map<String, Set<String>> outgoing = new LinkedHashMap<>();
        steps.forEach(s -> {
            incoming.putIfAbsent(s.id(), 0);
            outgoing.putIfAbsent(s.id(), new LinkedHashSet<>());
        });
        for (Step s : steps) {
            for (String dep : s.dependsOn()) {
                if (outgoing.get(dep).add(s.id())) {
                    incoming.merge(s.id(), 1, Integer::sum);
                }
            }
        }
        Deque<String> queue = new ArrayDeque<>();
        incoming.forEach((id, deg) -> {
            if (deg == 0) {
                queue.add(id);
            }
        });
        List<Step> ordered = new ArrayList<>();
        while (!queue.isEmpty()) {
            String id = queue.poll();
            ordered.add(byId.get(id));
            for (String next : outgoing.get(id)) {
                if (incoming.merge(next, -1, Integer::sum) == 0) {
                    queue.add(next);
                }
            }
        }
        if (ordered.size() != steps.size()) {
            throw new PlanException("计划步骤存在循环依赖");
        }
        return ordered;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }
}
