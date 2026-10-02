package com.eighthours.bovinbi.agent.orchestra.nodes;

import com.eighthours.bovinbi.agent.orchestra.AgentNode;
import com.eighthours.bovinbi.agent.orchestra.OrchestraContext;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.llm.LlmClient;
import com.eighthours.bovinbi.service.rag1.Rag1IntentService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * SQL 生成节点(写作 Agent):按 Schema + SideInfo + rag1 意图产 SQL。
 * 同一个节点承担首轮生成与修复轮改写 —— 黑板上有 rejection 即为修复模式,
 * 拒绝原因定向回喂(与 MultiAgentServiceImpl 的 Repair Agent 同一策略,表达为可编排节点)。
 */
@Component
@RequiredArgsConstructor
public class SqlWriterNode implements AgentNode {

    private static final String SQL_SYSTEM = """
            #Role: 你是数仓 NL2SQL 生成 Agent,熟悉 MySQL 方言。只负责生成 SQL,审查由下游 Agent 负责。
            #Rules:
            1. 只输出 JSON:{"sql": "...", "explanation": "一句话中文解释"},不要输出任何其他内容。
            2. 只允许一条 SELECT;表和列必须来自 #Schema,禁止编造(DO NOT hallucinate)。
            3. 聚合条件放 HAVING 而非 WHERE;除法用 NULLIF(x,0) 防除零;均值与率类用 ROUND(...,2)。
            4. 时间以 #SideInfo 为准:有区间生成半开区间 >= '起始日' AND < '结束日';无区间不加时间条件。
            5. 结果末尾必须有 LIMIT;输出列用中文别名。""";

    private final LlmClient llmClient;

    @Override
    public String name() {
        return "sql_writer";
    }

    @Override
    public void run(OrchestraContext ctx) throws Exception {
        long t0 = System.currentTimeMillis();
        boolean repair = ctx.sql() != null && !ctx.reviewPassed();
        String context = "#Schema: " + ctx.schema().schemaText()
                + "\n#Intent: " + Rag1IntentService.format(ctx.intent())
                + "\n#SideInfo: " + sideInfo(ctx)
                + "\n#Question: " + ctx.question()
                + (repair ? """

                ### 你上一版 SQL(未通过审查)
                ```sql
                %s
                ```
                ### 审查拒绝原因
                %s
                请定向修正后按同样格式输出 JSON。""".formatted(ctx.sql(), ctx.rejection()) : "");

        String raw = llmClient.chat(SQL_SYSTEM, context);
        JsonNode json = llmClient.extractJson(raw);
        String sql = json.path("sql").asText("").trim();
        if (sql.isEmpty()) {
            throw new BizException(502, "SQL 生成 Agent 未返回 SQL");
        }
        ctx.sql(sql);
        ctx.explanation(json.path("explanation").asText(""));
        ctx.record(repair ? "sql_writer(修复)" : name(), brief(sql), true, elapsed(t0),
                repair ? "按拒绝原因定向改写" : "生成 SQL");
    }

    private String sideInfo(OrchestraContext ctx) {
        return "今天日期: " + LocalDate.now() + ";时间理解: " + (ctx.timeRange() == null
                ? "未识别到明确时间,默认不添加时间过滤"
                : "已解析为区间 [" + ctx.timeRange().start() + ", " + ctx.timeRange().endExclusive()
                + ") 标签:" + ctx.timeRange().label());
    }

    private static int elapsed(long t0) {
        return (int) (System.currentTimeMillis() - t0);
    }

    private static String brief(String s) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
