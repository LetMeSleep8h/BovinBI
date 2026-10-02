package com.eighthours.bovinbi.agent.orchestra.nodes;

import com.eighthours.bovinbi.agent.orchestra.AgentNode;
import com.eighthours.bovinbi.agent.orchestra.OrchestraContext;
import com.eighthours.bovinbi.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 语义审查节点(审查 Agent 第二层,可关):问题意图 vs SQL 口径/维度/粒度。
 * 由编排里 stepIf 条件挂载(bovin.chat.orchestra.llm-review=false 即整步跳过),
 * 且仅在确定性审查通过后运行 —— 概率性审查永远排在确定性审查后面。
 */
@Component
@RequiredArgsConstructor
public class SemanticReviewNode implements AgentNode {

    private static final String REVIEW_SYSTEM = """
            #Role: 你是 SQL 审查 Agent。判断 SQL 是否正确回答了问题,只审查不改写。
            #审查维度: 1) 指标口径是否符合问题措辞(总量=SUM/平均=AVG/计数=COUNT DISTINCT);
            2) 分组维度与问题一致;3) 时间粒度与问题一致(月/天);4) 无编造的表/列/过滤值。
            #输出: 只输出 JSON {"verdict":"pass|fail","reason":"fail 时的一句话原因"},不要输出其他内容。""";

    private final LlmClient llmClient;

    @Override
    public String name() {
        return "reviewer(semantic)";
    }

    @Override
    public void run(OrchestraContext ctx) throws Exception {
        long t0 = System.currentTimeMillis();
        JsonNode verdict = llmClient.extractJson(llmClient.chat(REVIEW_SYSTEM,
                "#Question: " + ctx.question() + "\n#SQL:\n```sql\n" + ctx.guardedSql() + "\n```"));
        boolean pass = "pass".equalsIgnoreCase(verdict.path("verdict").asText(""));
        if (pass) {
            ctx.rejection(null);
        } else {
            ctx.rejection("语义审查: " + verdict.path("reason").asText("与问题意图不一致"));
        }
        ctx.record(name(), brief(ctx.guardedSql()), pass, elapsed(t0),
                pass ? "语义审查通过" : brief(ctx.rejection()));
    }

    private static int elapsed(long t0) {
        return (int) (System.currentTimeMillis() - t0);
    }

    private static String brief(String s) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
