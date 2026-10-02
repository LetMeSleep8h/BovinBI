package com.eighthours.bovinbi.agent.orchestra;

import com.eighthours.bovinbi.agent.orchestra.nodes.ExecutorNode;
import com.eighthours.bovinbi.agent.orchestra.nodes.GuardReviewNode;
import com.eighthours.bovinbi.agent.orchestra.nodes.InsightNode;
import com.eighthours.bovinbi.agent.orchestra.nodes.SchemaNode;
import com.eighthours.bovinbi.agent.orchestra.nodes.SemanticReviewNode;
import com.eighthours.bovinbi.agent.orchestra.nodes.SqlWriterNode;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.service.ChitChatHandler;
import com.eighthours.bovinbi.service.TimeRangeParser;
import com.eighthours.bovinbi.service.rag1.Rag1IntentService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 多 Agent 编排引擎(bovin.chat.engine=orchestra):Supervisor 路由 + 声明式工作流。
 * 与 multi-agent 引擎(代码写死的 for 循环流水线)的差别在"编排的表达方式":
 * - 路由前置:rag1 意图 → 闲聊直答,取数问题才进工作流(省一次无谓的 LLM 生成);
 * - 流程即数据:写→审(确定性)→审(语义,可关)组成修复环,执行/洞察是环外条件步骤,
 *   增删环节改 builder 装配,不改控制流代码;
 * - 失败契约与 multi-agent 一致:任何环节抛出 → 外壳(Nl2SqlService)降级规则引擎。
 * 轨迹与单 Agent 引擎同构(AgentTrace.ToolCall),前端无需感知引擎差异。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkflowOrchestrator {

    private final ObjectProvider<Rag1IntentService> rag1Intent;
    private final BovinProperties props;
    private final TimeRangeParser timeRangeParser;
    private final ChitChatHandler chitChatHandler;
    private final SchemaNode schemaNode;
    private final SqlWriterNode sqlWriterNode;
    private final GuardReviewNode guardReviewNode;
    private final SemanticReviewNode semanticReviewNode;
    private final ExecutorNode executorNode;
    private final InsightNode insightNode;

    public AnswerPayload answer(Long datasetId, String question, Long sessionId) {
        long t0 = System.currentTimeMillis();
        BovinProperties.Orchestra cfg = props.getChat().getOrchestra();
        OrchestraContext ctx = new OrchestraContext(datasetId, question, sessionId, cfg);

        // ---------- Supervisor:意图路由(确定性优先,rag1 增强) ----------
        Rag1IntentService rag1 = rag1Intent.getIfAvailable();
        if (rag1 != null) {
            ctx.intent(rag1.recognize(question));
        }
        boolean chitChat = chitChatHandler.isChitChat(question)
                || (ctx.intent() != null && ctx.intent().chitChat() && !chitChatHandler.hasDataSignal(question));
        if (chitChat) {
            return chitChatHandler.answer(question);
        }
        ctx.timeRange(timeRangeParser.parse(question));

        // ---------- 工作流装配:流程读起来就是执行序 ----------
        Workflow workflow = Workflow.builder()
                .step("schema", schemaNode)
                // 修复环:写 → 确定性审 → 语义审(可关且仅在守护通过后);环出口 = 审查通过
                .loop("write-review", cfg.getMaxRepairs() + 1, OrchestraContext::reviewPassed,
                        Workflow.Simple.step("sql_writer", sqlWriterNode),
                        Workflow.Simple.step("reviewer(guard)", guardReviewNode),
                        Workflow.Simple.stepIf("reviewer(semantic)",
                                c -> c.cfg().isLlmReview() && c.reviewPassed(), semanticReviewNode))
                // 环耗尽仍未通过时不再执行(条件步骤=编排里的失败短路),由下方统一抛出降级
                .stepIf("executor", OrchestraContext::reviewPassed, executorNode)
                .stepIf("insight", c -> c.result() != null, insightNode)
                .build();
        try {
            workflow.run(ctx);
        } catch (Exception e) {
            log.warn("orchestra 编排失败: {}", e.getMessage());
            throw e instanceof BizException biz ? biz : new BizException(502, e.getMessage());
        }
        if (ctx.result() == null) {
            throw new BizException(502, "多 Agent 编排修复预算耗尽,最后拒绝原因: " + ctx.rejection());
        }

        AnswerPayload p = new AnswerPayload();
        p.setSql(ctx.guardedSql());
        p.setExplanation(ctx.explanation());
        p.setColumns(ctx.result().columns());
        p.setRows(ctx.result().rows());
        p.setRowCount(ctx.result().rowCount());
        p.setChart(ctx.chart());
        p.setEngine("ORCHESTRA");
        p.setTrace(ctx.trace());
        p.setTookMs(System.currentTimeMillis() - t0);
        return p;
    }
}
