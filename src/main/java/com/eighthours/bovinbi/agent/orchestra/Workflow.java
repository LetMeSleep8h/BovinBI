package com.eighthours.bovinbi.agent.orchestra;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * 声明式多 Agent 工作流:把"编排放回数据"。
 * 与代码写死的流水线(MultiAgentServiceImpl 的 for 循环)相比,流程本身是可组合的描述:
 * - Simple:条件步骤(when 不满足则跳过,即编排里的"路由");
 * - Loop:修复环(循环执行 body 直到出口条件满足或轮次耗尽)。
 * 框架只负责控制流;节点做什么、记什么轨迹由 {@link AgentNode} 自己决定。
 */
@Slf4j
public final class Workflow {

    /** 步骤抽象:Simple=条件单步,Loop=带预算的循环体 */
    public sealed interface Step permits Simple, Loop {
    }

    /** 条件单步:when(ctx)=true 才执行节点(不满足=静默跳过,不算轨迹) */
    public record Simple(String name, Predicate<OrchestraContext> when, AgentNode node) implements Step {
        public static Simple step(String name, AgentNode node) {
            return new Simple(name, c -> true, node);
        }

        public static Simple stepIf(String name, Predicate<OrchestraContext> when, AgentNode node) {
            return new Simple(name, when, node);
        }
    }

    /**
     * 修复环:每轮顺序执行 body,轮末检查 exit —— 满足则出环;
     * maxRounds 轮耗尽仍不满足则带"最后一次状态"出环(由调用方决定成败)。
     */
    public record Loop(String name, int maxRounds, Predicate<OrchestraContext> exit, List<Step> body) implements Step {
    }

    private final List<Step> steps;

    private Workflow(List<Step> steps) {
        this.steps = List.copyOf(steps);
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 顺序执行;节点异常直接冒泡(基础设施故障由外壳统一降级,框架不做静默吞错) */
    public void run(OrchestraContext ctx) throws Exception {
        for (Step s : steps) {
            runStep(s, ctx);
        }
    }

    private void runStep(Step s, OrchestraContext ctx) throws Exception {
        switch (s) {
            case Simple st -> {
                if (st.when().test(ctx)) {
                    st.node().run(ctx);
                } else {
                    log.info("orchestra 步骤 [{}] 条件不满足,跳过", st.name());
                }
            }
            case Loop lp -> {
                for (int round = 1; round <= lp.maxRounds(); round++) {
                    for (Step body : lp.body()) {
                        runStep(body, ctx);
                    }
                    if (lp.exit().test(ctx)) {
                        log.info("orchestra 环 [{}] 第 {} 轮满足出口条件", lp.name(), round);
                        return;
                    }
                    log.info("orchestra 环 [{}] 第 {} 轮未通过,原因: {}", lp.name(), round, ctx.rejection());
                }
                log.warn("orchestra 环 [{}] {} 轮预算耗尽,最后状态: 通过={}", lp.name(), lp.maxRounds(), ctx.reviewPassed());
            }
        }
    }

    /** 流程组装器:步骤顺序即执行顺序,读起来就是执行序 */
    public static final class Builder {

        private final List<Step> steps = new ArrayList<>();

        public Builder step(String name, AgentNode node) {
            steps.add(Simple.step(name, node));
            return this;
        }

        public Builder stepIf(String name, Predicate<OrchestraContext> when, AgentNode node) {
            steps.add(Simple.stepIf(name, when, node));
            return this;
        }

        public Builder loop(String name, int maxRounds, Predicate<OrchestraContext> exit, Step... body) {
            steps.add(new Loop(name, maxRounds, exit, List.of(body)));
            return this;
        }

        public Workflow build() {
            return new Workflow(steps);
        }
    }
}
