package com.eighthours.bovinbi.agent.orchestra.nodes;

import com.eighthours.bovinbi.agent.orchestra.AgentNode;
import com.eighthours.bovinbi.agent.orchestra.OrchestraContext;
import com.eighthours.bovinbi.service.ChartAdvisor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 洞察节点(收尾 Agent):按问题与结果形态推荐图表,编排的"表达层"。
 * 与外壳(Nl2SqlService)兜底的 chartAdvisor 调用同源;orchestra 引擎自带此步,
 * 外壳见 chart 已产出即不再重复计算。
 */
@Component
@RequiredArgsConstructor
public class InsightNode implements AgentNode {

    private final ChartAdvisor chartAdvisor;

    @Override
    public String name() {
        return "insight";
    }

    @Override
    public void run(OrchestraContext ctx) {
        long t0 = System.currentTimeMillis();
        ctx.chart(chartAdvisor.advise(ctx.question(), ctx.result().columns(), ctx.result().rows()));
        ctx.record(name(), "", true, elapsed(t0), "图表推荐: " + (ctx.chart() == null ? "无" : ctx.chart().getType()));
    }

    private static int elapsed(long t0) {
        return (int) (System.currentTimeMillis() - t0);
    }
}
