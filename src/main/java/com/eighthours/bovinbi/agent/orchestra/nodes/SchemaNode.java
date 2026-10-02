package com.eighthours.bovinbi.agent.orchestra.nodes;

import com.eighthours.bovinbi.agent.orchestra.AgentNode;
import com.eighthours.bovinbi.agent.orchestra.OrchestraContext;
import com.eighthours.bovinbi.service.SchemaRetriever;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Schema 召回节点:把"紧凑 Schema 文本 + 表白名单"放上黑板,供 SqlWriter 生成、GuardReview 守护。
 * 与单 Agent 引擎的 getSchema 工具同源(同一个 SchemaRetriever),召回策略升级两边同时受益。
 */
@Component
@RequiredArgsConstructor
public class SchemaNode implements AgentNode {

    private final SchemaRetriever schemaRetriever;

    @Override
    public String name() {
        return "schema";
    }

    @Override
    public void run(OrchestraContext ctx) {
        long t0 = System.currentTimeMillis();
        SchemaRetriever.LinkedSchema linked = schemaRetriever.retrieve(ctx.datasetId(), ctx.question());
        ctx.schema(linked);
        ctx.record(name(), brief(ctx.question()), true, elapsed(t0),
                "白名单: " + linked.whitelist());
    }

    private static int elapsed(long t0) {
        return (int) (System.currentTimeMillis() - t0);
    }

    private static String brief(String s) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
