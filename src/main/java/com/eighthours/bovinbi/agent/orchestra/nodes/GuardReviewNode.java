package com.eighthours.bovinbi.agent.orchestra.nodes;

import com.eighthours.bovinbi.agent.orchestra.AgentNode;
import com.eighthours.bovinbi.agent.orchestra.OrchestraContext;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.service.SqlGuard;
import com.eighthours.bovinbi.service.TimeRange;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 确定性审查节点(审查 Agent 第一层):SQL 守护 + 时间区间对账,零成本零幻觉。
 * 拒绝不抛异常 —— 原因写黑板,驱动修复环下一轮定向改写(错误即数据)。
 * 时间对账与 MultiAgentServiceImpl 保持同一规则(识别出区间 → SQL 必须含同字面量半开区间),
 * 两处刻意各自持有实现:不同引擎可独立回滚,审查规则演进时互不绑架。
 */
@Component
@RequiredArgsConstructor
public class GuardReviewNode implements AgentNode {

    private static final Pattern RE_START = Pattern.compile("record_date\\s*>=\\s*'(\\d{4}-\\d{2}-\\d{2})'");
    private static final Pattern RE_END = Pattern.compile("record_date\\s*<\\s*'(\\d{4}-\\d{2}-\\d{2})'");

    private final SqlGuard sqlGuard;

    @Override
    public String name() {
        return "reviewer(guard)";
    }

    @Override
    public void run(OrchestraContext ctx) {
        try {
            String guarded = sqlGuard.validate(ctx.sql(), ctx.schema().whitelist());
            String timeIssue = timeMismatch(guarded, ctx.timeRange());
            if (timeIssue != null) {
                ctx.rejection(timeIssue);
                ctx.record(name(), brief(ctx.sql()), false, 0, brief(timeIssue));
                return;
            }
            ctx.guardedSql(guarded);
            ctx.rejection(null); // 通过即清拒绝标记,修复环据此出口
            ctx.record(name(), brief(guarded), true, 0, "守护与时间对账通过");
        } catch (BizException e) {
            ctx.rejection("守护拒绝: " + e.getMessage());
            ctx.record(name(), brief(ctx.sql()), false, 0, brief(ctx.rejection()));
        }
    }

    /** 时间对账(确定性):解析出区间 → SQL 必须含同字面量半开区间;未解析出 → 不应含时间过滤 */
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

    private static String brief(String s) {
        String one = s == null ? "" : s.replaceAll("\\s+", " ").trim();
        return one.length() <= 120 ? one : one.substring(0, 120) + "…";
    }
}
