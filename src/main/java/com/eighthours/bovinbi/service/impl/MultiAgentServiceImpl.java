package com.eighthours.bovinbi.service.impl;

import com.eighthours.bovinbi.agent.AgentTrace;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.llm.LlmClient;
import com.eighthours.bovinbi.service.MultiAgentService;
import com.eighthours.bovinbi.service.QueryExecutor;
import com.eighthours.bovinbi.service.SchemaRetriever;
import com.eighthours.bovinbi.service.SqlGuard;
import com.eighthours.bovinbi.service.TimeRange;
import com.eighthours.bovinbi.service.TimeRangeParser;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多 Agent 流水线实现(SQL Agent → Reviewer → Repair Agent):
 * - SQL Agent:一次 LLM 调用按 Schema+SideInfo 产 SQL(单轮、职责单一,无工具循环);
 * - Reviewer:双层 —— 确定性审查(SQL 守护 + 时间区间与 TimeRangeParser 对账,零成本零幻觉)
 *   + 可选 LLM 语义审查(问题意图 vs SQL 口径/维度/粒度);时间对账把"LLM 抄错/漏写时间条件"
 *   这一大类语义错误变成确定性拦截,是单 Agent 循环没有的硬闸;
 * - Repair Agent:拿着拒绝原因定向重写,轮数受 bovin.chat.multi-agent.max-repairs 预算约束;
 * - 全链路失败抛出 → Nl2SqlService 降级规则引擎;trace 记录每个 Agent 的职责步骤。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MultiAgentServiceImpl implements MultiAgentService {

    private final SchemaRetriever schemaRetriever;
    private final TimeRangeParser timeRangeParser;
    private final LlmClient llmClient;
    private final SqlGuard sqlGuard;
    private final QueryExecutor queryExecutor;
    private final BovinProperties props;

    /** SQL Agent 系统提示词:与 NL2SQL 管线同一套硬约束,但只负责"写",不负责"审" */
    private static final String SQL_AGENT_SYSTEM = """
            #Role: 你是数仓 NL2SQL 生成 Agent,熟悉 MySQL 方言。只负责生成 SQL,审查由下游 Agent 负责。
            #Rules:
            1. 只输出 JSON:{"sql": "...", "explanation": "一句话中文解释"},不要输出任何其他内容。
            2. 只允许一条 SELECT;表和列必须来自 #Schema,禁止编造(DO NOT hallucinate)。
            3. 聚合条件放 HAVING 而非 WHERE;除法用 NULLIF(x,0) 防除零;均值与率类用 ROUND(...,2)。
            4. 时间以 #SideInfo 为准:有区间生成半开区间 >= '起始日' AND < '结束日';无区间不加时间条件。
            5. 结果末尾必须有 LIMIT;输出列用中文别名。""";

    private static final String REVIEW_SYSTEM = """
            #Role: 你是 SQL 审查 Agent。判断 SQL 是否正确回答了问题,只审查不改写。
            #审查维度: 1) 指标口径是否符合问题措辞(总量=SUM/平均=AVG/计数=COUNT DISTINCT);
            2) 分组维度与问题一致;3) 时间粒度与问题一致(月/天);4) 无编造的表/列/过滤值。
            #输出: 只输出 JSON {"verdict":"pass|fail","reason":"fail 时的一句话原因"},不要输出其他内容。""";

    private static final Pattern RE_START = Pattern.compile("record_date\\s*>=\\s*'(\\d{4}-\\d{2}-\\d{2})'");
    private static final Pattern RE_END = Pattern.compile("record_date\\s*<\\s*'(\\d{4}-\\d{2}-\\d{2})'");

    @Override
    public AnswerPayload answer(Long datasetId, String question, Long sessionId) {
        long t0 = System.currentTimeMillis();
        BovinProperties.MultiAgent cfg = props.getChat().getMultiAgent();

        TimeRange tr = timeRangeParser.parse(question);
        SchemaRetriever.LinkedSchema schema = schemaRetriever.retrieve(datasetId, question);
        String sideInfo = "今天日期: " + LocalDate.now() + ";时间理解: " + (tr == null
                ? "未识别到明确时间,默认不添加时间过滤"
                : "已解析为区间 [" + tr.start() + ", " + tr.endExclusive() + ") 标签:" + tr.label());
        String context = "#Schema: " + schema.schemaText() + "\n#SideInfo: " + sideInfo + "\n#Question: " + question;

        List<AgentTrace.ToolCall> trace = new ArrayList<>();
        int[] seq = {0};
        String sql = null;
        String explanation = "";
        String reason = null;
        int rounds = 1 + Math.max(0, cfg.getMaxRepairs());

        for (int round = 0; round < rounds; round++) {
            // ---------- SQL Agent(首轮) / Repair Agent(后续轮) ----------
            long ta = System.currentTimeMillis();
            String raw;
            if (round == 0) {
                raw = llmClient.chat(SQL_AGENT_SYSTEM, context);
            } else {
                raw = llmClient.chat(SQL_AGENT_SYSTEM, context + """

                        ### 你上一版 SQL(未通过审查)
                        ```sql
                        %s
                        ```
                        ### 审查拒绝原因
                        %s
                        请定向修正后按同样格式输出 JSON。""".formatted(sql, reason));
                trace.add(new AgentTrace.ToolCall(++seq[0], "repair_agent", brief(sql), true,
                        (int) (System.currentTimeMillis() - ta), "第 " + round + " 轮修复"));
            }
            JsonNode json = llmClient.extractJson(raw);
            sql = json.path("sql").asText("").trim();
            if (sql.isEmpty()) {
                throw new BizException(502, "Agent 未返回 SQL");
            }
            explanation = json.path("explanation").asText("");
            if (round == 0) {
                trace.add(new AgentTrace.ToolCall(++seq[0], "sql_agent", brief(sql), true,
                        (int) (System.currentTimeMillis() - ta), "生成 SQL"));
            }

            // ---------- Reviewer 第一层:确定性审查(守护 + 时间对账) ----------
            String guarded;
            try {
                guarded = sqlGuard.validate(sql, schema.whitelist());
            } catch (BizException e) {
                reason = "守护拒绝: " + e.getMessage();
                trace.add(new AgentTrace.ToolCall(++seq[0], "reviewer", brief(sql), false,
                        0, brief(reason)));
                continue;
            }
            String timeIssue = timeMismatch(guarded, tr);
            if (timeIssue != null) {
                reason = timeIssue;
                trace.add(new AgentTrace.ToolCall(++seq[0], "reviewer", brief(sql), false,
                        0, brief(reason)));
                continue;
            }

            // ---------- Reviewer 第二层:LLM 语义审查(可关) ----------
            if (cfg.isLlmReview()) {
                long tr0 = System.currentTimeMillis();
                JsonNode verdict = llmClient.extractJson(llmClient.chat(REVIEW_SYSTEM,
                        "#Question: " + question + "\n#SQL:\n```sql\n" + guarded + "\n```"));
                boolean pass = "pass".equalsIgnoreCase(verdict.path("verdict").asText(""));
                trace.add(new AgentTrace.ToolCall(++seq[0], "reviewer", brief(guarded), pass,
                        (int) (System.currentTimeMillis() - tr0), pass ? "语义审查通过" : brief(verdict.path("reason").asText("语义审查未通过"))));
                if (!pass) {
                    reason = "语义审查: " + verdict.path("reason").asText("与问题意图不一致");
                    continue;
                }
            }

            // ---------- 审查通过:执行并组装 ----------
            ExecResult r = queryExecutor.execute(guarded);
            trace.add(new AgentTrace.ToolCall(++seq[0], "execute", brief(guarded), true,
                    (int) r.tookMs(), r.rowCount() + " 行结果"));
            AnswerPayload p = new AnswerPayload();
            p.setSql(guarded);
            p.setExplanation(explanation);
            p.setColumns(r.columns());
            p.setRows(r.rows());
            p.setRowCount(r.rowCount());
            p.setEngine("MULTI_AGENT");
            p.setTrace(trace);
            p.setTookMs(System.currentTimeMillis() - t0);
            return p;
        }
        throw new BizException(502, "多 Agent 修复预算耗尽,最后拒绝原因: " + reason);
    }

    /**
     * 时间对账(确定性):解析出区间 → SQL 必须含同字面量的半开区间;
     * 未解析出 → SQL 不应含 record_date 过滤。把"模型抄错/漏写时间"从语义问题变成确定性拦截。
     */
    private String timeMismatch(String sql, TimeRange tr) {
        Matcher ms = RE_START.matcher(sql);
        String start = ms.find() ? ms.group(1) : null;
        Matcher me = RE_END.matcher(sql);
        String end = me.find() ? me.group(1) : null;
        if (tr == null) {
            return (start != null || end != null)
                    ? "问题未指定时间,SQL 不应添加 record_date 时间过滤" : null;
        }
        String es = TimeRange.F.format(tr.start());
        String ee = TimeRange.F.format(tr.endExclusive());
        return es.equals(start) && ee.equals(end) ? null
                : "时间区间与解析结果不一致:期望 >= '" + es + "' AND < '" + ee + "',实际 " + start + " / " + end;
    }

    private String brief(String s) {
        if (s == null) return "";
        String one = s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
