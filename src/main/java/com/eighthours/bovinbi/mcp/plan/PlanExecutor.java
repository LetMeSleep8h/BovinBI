package com.eighthours.bovinbi.mcp.plan;

import com.eighthours.bovinbi.agent.AgentTrace;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.ColInfo;
import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.llm.LlmClient;
import com.eighthours.bovinbi.mcp.McpToolRegistry;
import com.eighthours.bovinbi.mcp.ToolSpec;
import com.eighthours.bovinbi.service.ChartAdvisor;
import com.eighthours.bovinbi.trace.TraceHub;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多步计划链路(融合 ai-picture-editor 的 plan/verify/拓扑执行骨架,
 * 叠加本项目差异化的三道本项目独有闸门):
 *   Planner(LLM 出结构化 JSON 计划) → PlanOps 四道校验(含写操作拦截) →
 *   按拓扑序逐步执行:每步经 McpToolRegistry(守护/表白名单/预算配额在同一入口) →
 *   SSE 逐步推送执行进度(复用 TraceHub,前端与实时工作流同一渲染)。
 * 与参考实现的差异(面试差异化点):参考实现校验后直接执行;本实现每步仍过
 * SqlGuard + 表级预算 + 每工具配额 —— "骨架是它的,预算和护栏是我的"。
 */
@Slf4j
@Service
public class PlanExecutor {

    /** 单步执行结果暂存,供后续步骤按 step id 引用(依赖语义的物质基础) */
    private static final Map<Long, Map<String, StepResult>> STEP_RESULTS = new ConcurrentHashMap<>();

    public record StepResult(String tool, boolean ok, String summary, ExecResult result) {
    }

    private final LlmClient llmClient;
    private final McpToolRegistry registry;
    private final ObjectMapper objectMapper;
    private final ChartAdvisor chartAdvisor;

    private static final String PLANNER_SYSTEM = """
            你是数仓分析任务的规划器。把用户的分析意图拆解为最多 %d 步的工具调用计划。
            可用工具及参数见 %s。
            规则:
            1. 只输出 JSON 数组,每步:{"id":"s1","tool":"工具名","params":{...},"depends_on":["依赖步骤id"]}
            2. depends_on 缺省表示依赖上一步;不要输出计划之外的任何解释。
            3. 最后一步的结果必须能直接回答用户问题;中间步骤的结果供后续步骤使用。
            4. 禁止编造工具名或参数;写操作工具(导入类)禁止出现在计划中。""";

    public PlanExecutor(LlmClient llmClient, McpToolRegistry registry, ObjectMapper objectMapper,
                        ChartAdvisor chartAdvisor) {
        this.llmClient = llmClient;
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.chartAdvisor = chartAdvisor;
    }

    /** 生成计划(只规划不执行):四道校验后的拓扑序步骤,前端可确认后调 execute */
    public List<PlanOps.Step> plan(Long datasetId, String question, Long sessionId) {
        List<ToolSpec> catalog = registry.specs();
        String toolLines = catalog.stream()
                .map(t -> "- %s: %s 参数:%s".formatted(t.name(), t.description(), t.inputSchema().keySet()))
                .reduce((a, b) -> a + "\n" + b).orElse("");
        String system = PLANNER_SYSTEM.formatted(com.eighthours.bovinbi.mcp.plan.PlanOps.MAX_STEPS, toolLines);

        String raw = llmClient.chat(system,
                (datasetId == null ? "" : "数据集ID: " + datasetId + "\n") + "分析意图: " + question);
        JsonNode planNode;
        try {
            planNode = objectMapper.readTree(raw);
        } catch (Exception ignore) {
            planNode = null;
        }
        if (planNode == null || !planNode.isArray() || planNode.isEmpty()) {
            JsonNode inner = null;
            try {
                inner = objectMapper.readTree(raw);
            } catch (Exception ignore) {
                // fallthrough
            }
            if (inner != null && inner.has("plan")) {
                planNode = inner.get("plan");
            }
        }
        if (planNode == null || !planNode.isArray() || planNode.isEmpty()) {
            throw new BizException(502, "Planner 未能产出可解析的计划");
        }
        List<Map<String, Object>> rawPlan = new ArrayList<>();
        planNode.forEach(n -> rawPlan.add(objectMapper.convertValue(n, Map.class)));

        Set<String> mutators = new HashSet<>();
        catalog.forEach(t -> {
            if (t.mutator()) {
                mutators.add(t.name());
            }
        });
        Set<String> names = new HashSet<>();
        catalog.forEach(t -> names.add(t.name()));
        PlanOps ops = new PlanOps(names, mutators);
        List<PlanOps.Step> steps = ops.assemble(rawPlan);

        // 校验反馈环(verify 的思想):计划涉及 executeSql 时,先真实取一次 Schema,
        // 让 Planner 基于真实表/列修订计划 —— 消除"编造表名"这类执行期必败步骤
        boolean needsSql = steps.stream().anyMatch(st -> "executeSql".equals(st.tool()));
        if (needsSql && datasetId != null) {
            String schemaText;
            try {
                schemaText = registry.call("getSchema", Map.of("datasetId", datasetId, "keywords", question)).text();
            } catch (Exception e) {
                return steps; // Schema 取不到就按原计划走,执行层守护仍兜底
            }
            StringBuilder sqls = new StringBuilder();
            steps.stream().filter(st -> "executeSql".equals(st.tool()))
                    .forEach(st -> sqls.append(st.id()).append(": ")
                            .append(st.params().get("sql")).append('\n'));
            String revise = "以下是你此前给出的计划中 executeSql 步骤使用的 SQL,与真实 Schema 对照:\n"
                    + "### 真实 Schema\n" + schemaText + "\n"
                    + "### 你此前计划中的 SQL\n" + sqls + "\n"
                    + "只输出修订后的 JSON 计划数组(结构与之前相同),SQL 必须只用真实 Schema 中的表和列。";
            String raw2 = llmClient.chat(system, "数据集ID: " + datasetId
                    + "\n分析意图: " + question + "\n\n" + revise);
            try {
                JsonNode planNode2 = objectMapper.readTree(raw2);
                if (planNode2.isArray() && !planNode2.isEmpty()) {
                    List<Map<String, Object>> raw2List = new ArrayList<>();
                    planNode2.forEach(n -> raw2List.add(objectMapper.convertValue(n, Map.class)));
                    List<PlanOps.Step> revised = ops.assemble(raw2List);
                    if (revised.stream().anyMatch(st -> "executeSql".equals(st.tool()))) {
                        return revised;
                    }
                }
            } catch (Exception e) {
                log.warn("计划修订轮未产出可解析计划,按首轮计划执行: {}", e.getMessage());
            }
        }
        return steps;
    }

    /**
     * 按拓扑序执行已确认的计划:每步经注册中心(守护/配额同入口),结果登记供依赖步引用,
     * SSE 逐步推送。任一步失败即终止并返回失败载荷(剩余步骤不再执行)。
     */
    @SuppressWarnings("unchecked")
    public AnswerPayload execute(Long datasetId, String question, Long sessionId, long queryId,
                                 List<PlanOps.Step> steps) {
        long t0 = System.currentTimeMillis();
        Map<String, StepResult> results = STEP_RESULTS.computeIfAbsent(queryId, k -> new ConcurrentHashMap<>());
        List<AgentTrace.ToolCall> trace = new ArrayList<>();
        int[] seq = {0};
        ExecResult last = null;
        String lastSql = null;

        try {
            for (PlanOps.Step step : steps) {
                long ts = System.currentTimeMillis();
                Map<String, Object> args = new LinkedHashMap<>(step.params());
                args.putIfAbsent("datasetId", datasetId);
                // 依赖步产物注入:params 里值为 "$s2" 的参数替换为该步结果摘要
                args.replaceAll((k, v) -> v instanceof String s && s.startsWith("$")
                        ? String.valueOf(results.getOrDefault(s.substring(1),
                                new StepResult("", false, "(无前置结果)", null)).summary())
                        : v);

                TraceHub.publish(queryId, "计划步骤 " + step.id(), step.tool() + " 执行中", true);
                var r = registry.call(step.tool(), args);
                StepResult sr = new StepResult(step.tool(), !r.isError(), r.text(), null);
                results.put(step.id(), sr);
                trace.add(new AgentTrace.ToolCall(++seq[0], step.id() + ":" + step.tool(),
                        brief(r.text()), !r.isError(), (int) (System.currentTimeMillis() - ts),
                        brief(r.text())));
                TraceHub.publish(queryId, step.id() + ":" + step.tool(),
                        brief(r.text()), !r.isError());
                if (r.isError()) {
                    return payload(null, null, true, "计划步骤 " + step.id() + "(" + step.tool()
                            + ") 失败: " + brief(r.text()), trace, t0, queryId);
                }
                // executeSql 的结果记下来:最后一步成功执行的 SQL 作为最终答案载体
                if ("executeSql".equals(step.tool()) && step.params().get("sql") != null) {
                    lastSql = String.valueOf(step.params().get("sql"));
                    last = execForChart(datasetId, lastSql);
                }
            }
        } finally {
            STEP_RESULTS.remove(queryId);
        }
        return payload(lastSql, last, false, "计划 " + steps.size() + " 步全部执行完成", trace, t0, queryId);
    }

    private ExecResult execForChart(Long datasetId, String sql) {
        try {
            return registryCallExec(datasetId, sql);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private ExecResult registryCallExec(Long datasetId, String sql) {
        var r = registry.call("executeSql", Map.of("datasetId", datasetId, "sql", sql));
        if (r.isError()) {
            return null;
        }
        // 从预览文本重建轻量结果供图表推荐(行数以 LIMIT 内预览为准)
        List<ColInfo> cols = new ArrayList<>();
        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
        for (String line : (r.text() == null ? "" : r.text()).split("\n")) {
            String l = line.trim();
            if (l.startsWith("列:")) {
                for (String n : l.substring(2).split("\\|")) {
                    if (!n.isBlank()) {
                        cols.add(new ColInfo(n.trim(), "VARCHAR"));
                    }
                }
            } else if (!cols.isEmpty() && !l.startsWith("执行成功") && !l.startsWith("前 ")
                    && l.contains("|")) {
                String[] vals = l.split("\\|");
                if (vals.length == cols.size()) {
                    LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                    for (int i = 0; i < vals.length; i++) {
                        row.put(cols.get(i).name(), vals[i].trim());
                    }
                    rows.add(row);
                }
            }
        }
        return new ExecResult(cols, rows, rows.size(), 0);
    }

    private AnswerPayload payload(String sql, ExecResult exec, boolean fallback, String explanation,
                                  List<AgentTrace.ToolCall> trace, long t0, long queryId) {
        AnswerPayload p = new AnswerPayload();
        p.setEngine("PLAN");
        p.setTrace(trace);
        p.setTookMs(System.currentTimeMillis() - t0);
        p.setExplanation(explanation);
        if (fallback || exec == null) {
            p.setFallback(true);
            p.setFallbackHint(explanation);
            return p;
        }
        p.setSql(sql);
        p.setColumns(exec.columns());
        p.setRows(exec.rows());
        p.setRowCount(exec.rowCount());
        p.setChart(chartAdvisor.advise(sql, exec.columns(), exec.rows()));
        return p;
    }

    private static String brief(String s) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
