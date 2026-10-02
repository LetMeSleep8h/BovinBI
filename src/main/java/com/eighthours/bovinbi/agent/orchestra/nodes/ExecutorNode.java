package com.eighthours.bovinbi.agent.orchestra.nodes;

import com.eighthours.bovinbi.agent.orchestra.AgentNode;
import com.eighthours.bovinbi.agent.orchestra.OrchestraContext;
import com.eighthours.bovinbi.service.QueryExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 执行节点:守护通过的 SQL 交只读执行器。走到这一步时 SQL 已过两道审查,
 * 执行异常(超时/引擎错误)直接冒泡由外壳降级 —— 与"守护拒绝走修复环"是两条不同的失败通道。
 */
@Component
@RequiredArgsConstructor
public class ExecutorNode implements AgentNode {

    private final QueryExecutor queryExecutor;

    @Override
    public String name() {
        return "executor";
    }

    @Override
    public void run(OrchestraContext ctx) {
        long t0 = System.currentTimeMillis();
        try {
            var r = queryExecutor.execute(ctx.guardedSql());
            ctx.result(r);
            ctx.record(name(), brief(ctx.guardedSql()), true, (int) r.tookMs(), r.rowCount() + " 行结果");
        } catch (Exception e) {
            ctx.record(name(), brief(ctx.guardedSql()), false, elapsed(t0), brief(e.getMessage()));
            throw e;
        }
    }

    private static int elapsed(long t0) {
        return (int) (System.currentTimeMillis() - t0);
    }

    private static String brief(String s) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
