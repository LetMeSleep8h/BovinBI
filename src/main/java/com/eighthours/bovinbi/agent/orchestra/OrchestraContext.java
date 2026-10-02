package com.eighthours.bovinbi.agent.orchestra;

import com.eighthours.bovinbi.agent.AgentTrace;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.ChartSpec;
import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.service.SchemaRetriever;
import com.eighthours.bovinbi.service.TimeRange;
import com.eighthours.bovinbi.service.rag1.Rag1IntentService;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次编排运行的黑板(Blackboard):节点间不互相调用,只读写黑板。
 * 与 AgentRunContext(ThreadLocal,服务单 Agent 工具循环)的分工:
 * 这里是显式传参的多 Agent 编排状态,无 ThreadLocal、天然请求隔离。
 */
public class OrchestraContext {

    private final Long datasetId;
    private final String question;
    private final Long sessionId;
    private final BovinProperties.Orchestra cfg;

    private Rag1IntentService.IntentResult intent;   // Supervisor 路由依据(rag1,可为 null=未启用)
    private TimeRange timeRange;                     // 确定性时间解析
    private SchemaRetriever.LinkedSchema schema;     // Schema 召回产物
    private String sql;                              // 当前候选 SQL(SqlWriter 产物)
    private String guardedSql;                       // 守护通过后的 SQL(Reviewer 产物)
    private String explanation = "";                 // 生成器的一句话解释
    private String rejection;                        // 最近一次审查拒绝原因(null=通过)
    private ExecResult result;                       // 执行结果(Executor 产物)
    private ChartSpec chart;                         // 图表建议(Insight 产物)

    private final List<AgentTrace.ToolCall> trace = new ArrayList<>();
    private int seq;

    public OrchestraContext(Long datasetId, String question, Long sessionId, BovinProperties.Orchestra cfg) {
        this.datasetId = datasetId;
        this.question = question;
        this.sessionId = sessionId;
        this.cfg = cfg;
    }

    /** 节点记录轨迹(与单 Agent 引擎同构的 ToolCall,前端复用同一渲染) */
    public void record(String node, String args, boolean ok, int tookMs, String summary) {
        if (trace.size() < 64) {
            trace.add(new AgentTrace.ToolCall(++seq, node, args, ok, tookMs, summary));
        }
    }

    public boolean reviewPassed() {
        return rejection == null;
    }

    // ---- 以下是纯读写胶水,保持节点代码聚焦业务 ----

    public Long datasetId() {
        return datasetId;
    }

    public String question() {
        return question;
    }

    public Long sessionId() {
        return sessionId;
    }

    public BovinProperties.Orchestra cfg() {
        return cfg;
    }

    public Rag1IntentService.IntentResult intent() {
        return intent;
    }

    public void intent(Rag1IntentService.IntentResult intent) {
        this.intent = intent;
    }

    public TimeRange timeRange() {
        return timeRange;
    }

    public void timeRange(TimeRange timeRange) {
        this.timeRange = timeRange;
    }

    public SchemaRetriever.LinkedSchema schema() {
        return schema;
    }

    public void schema(SchemaRetriever.LinkedSchema schema) {
        this.schema = schema;
    }

    public String sql() {
        return sql;
    }

    public void sql(String sql) {
        this.sql = sql;
    }

    public String guardedSql() {
        return guardedSql;
    }

    public void guardedSql(String guardedSql) {
        this.guardedSql = guardedSql;
    }

    public String explanation() {
        return explanation;
    }

    public void explanation(String explanation) {
        this.explanation = explanation;
    }

    public String rejection() {
        return rejection;
    }

    public void rejection(String rejection) {
        this.rejection = rejection;
    }

    public ExecResult result() {
        return result;
    }

    public void result(ExecResult result) {
        this.result = result;
    }

    public ChartSpec chart() {
        return chart;
    }

    public void chart(ChartSpec chart) {
        this.chart = chart;
    }

    public List<AgentTrace.ToolCall> trace() {
        return trace;
    }
}
