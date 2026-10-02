package com.eighthours.bovinbi.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 每用户每日 token 用量(独立存储,按调用累计):
 * - 计量点在 LangChain4jClient(项目唯一的直连 LLM 出口),从响应的 tokenUsage 取数;
 *   uid 取请求线程的 UserContext(流式链路已显式传递身份);
 * - 上行/下行/请求数按 (user_id, usage_date) 主键 upsert 累计,重启不丢;
 * - 查询:普通用户只见自己;ADMIN 可带 all=true 看全员(含用户名)。
 * 口径说明:走 LLM 网关的调用由 gateway_usage 单独计量;Agent 循环内联构建的
 * 模型实例(绕过 LlmClient)暂未计入 —— 收敛到统一出口是后续项。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TokenUsageService {

    private final JdbcTemplate jdbcTemplate;

    /** 累计一次调用(null 用量按 0 token 记请求数 —— 部分兼容端点不回 usage) */
    public void record(Long userId, Integer promptTokens, Integer completionTokens) {
        if (userId == null) {
            return; // 非用户态调用(启动装载/内部任务)不计
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO token_usage_daily (user_id, usage_date, prompt_tokens, completion_tokens, requests)
                    VALUES (?, CURDATE(), ?, ?, 1)
                    ON DUPLICATE KEY UPDATE
                        prompt_tokens = prompt_tokens + VALUES(prompt_tokens),
                        completion_tokens = completion_tokens + VALUES(completion_tokens),
                        requests = requests + 1
                    """, userId,
                    promptTokens == null ? 0 : promptTokens,
                    completionTokens == null ? 0 : completionTokens);
        } catch (Exception e) {
            log.warn("token 用量记录失败(不影响主链路): {}", e.getMessage());
        }
    }

    /** 近 N 天用量:普通用户只查自己;all=true(ADMIN) 查全员并带用户名 */
    public List<Map<String, Object>> recent(long userId, boolean admin, boolean all, int days) {
        if (all && admin) {
            return jdbcTemplate.queryForList("""
                    SELECT t.usage_date AS usageDate, u.username AS username,
                           t.prompt_tokens AS promptTokens, t.completion_tokens AS completionTokens,
                           (t.prompt_tokens + t.completion_tokens) AS totalTokens, t.requests AS requests
                    FROM token_usage_daily t JOIN sys_user u ON t.user_id = u.id
                    WHERE t.usage_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
                    ORDER BY t.usage_date DESC, totalTokens DESC
                    """, days);
        }
        return jdbcTemplate.queryForList("""
                SELECT usage_date AS usageDate, NULL AS username,
                       prompt_tokens AS promptTokens, completion_tokens AS completionTokens,
                       (prompt_tokens + completion_tokens) AS totalTokens, requests AS requests
                FROM token_usage_daily
                WHERE user_id = ? AND usage_date >= DATE_SUB(CURDATE(), INTERVAL ? DAY)
                ORDER BY usage_date DESC
                """, userId, days);
    }

    /** 今日汇总(self 或全员) */
    public Map<String, Object> today(long userId, boolean admin, boolean all) {
        List<Map<String, Object>> rows = (all && admin)
                ? jdbcTemplate.queryForList("""
                        SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS tokens,
                               COALESCE(SUM(requests), 0) AS requests
                        FROM token_usage_daily WHERE usage_date = CURDATE()""")
                : jdbcTemplate.queryForList("""
                        SELECT COALESCE(SUM(prompt_tokens + completion_tokens), 0) AS tokens,
                               COALESCE(SUM(requests), 0) AS requests
                        FROM token_usage_daily WHERE user_id = ? AND usage_date = CURDATE()""", userId);
        return rows.isEmpty() ? Map.of("tokens", 0, "requests", 0) : rows.get(0);
    }

    /** 供测试与排障:清某用户当日记录 */
    public void clearToday(long userId) {
        jdbcTemplate.update("DELETE FROM token_usage_daily WHERE user_id = ? AND usage_date = CURDATE()", userId);
        log.info("清理用户 {} 当日 token 用量记录", userId);
    }

    public LocalDate todayDate() {
        return LocalDate.now();
    }
}
