package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.dto.LoginResp;
import com.eighthours.bovinbi.security.UserContext;
import com.eighthours.bovinbi.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 每用户每日 token 用量契约:
 * 1) 累计:同日多次调用 upsert 累加(上行/下行/请求数),不是覆盖;
 * 2) 隔离:A 用户查不到 B 用户的记录;ADMIN all 视角可见两人;
 * 3) null 用量(端点未回 usage)记请求数、token 记 0;
 * 4) 今日汇总口径 = 当日各行之和。
 */
@SpringBootTest
class TokenUsageServiceTest extends MySqlTestBase {

    @Autowired
    private TokenUsageService tokenUsageService;
    @Autowired
    private AuthService authService;

    private final String uniq = Long.toString(System.nanoTime(), 36);

    @AfterEach
    void clean() {
        UserContext.clear();
    }

    private Long user(String prefix) {
        LoginResp r = authService.register(prefix + "_" + uniq, "abcd1234", null);
        return r.id();
    }

    @Test
    void accumulatesPerUserPerDay() {
        Long uid = user("tu_a");
        tokenUsageService.clearToday(uid);
        tokenUsageService.record(uid, 100, 50);
        tokenUsageService.record(uid, 200, 80);

        List<Map<String, Object>> rows = tokenUsageService.recent(uid, false, false, 7);
        assertEquals(1, rows.size(), "同日累计为一行");
        Map<String, Object> row = rows.get(0);
        assertEquals(300, ((Number) row.get("promptTokens")).intValue());
        assertEquals(130, ((Number) row.get("completionTokens")).intValue());
        assertEquals(430, ((Number) row.get("totalTokens")).intValue());
        assertEquals(2, ((Number) row.get("requests")).intValue());

        Map<String, Object> today = tokenUsageService.today(uid, false, false);
        assertEquals(430, ((Number) today.get("tokens")).intValue());
        assertEquals(2, ((Number) today.get("requests")).intValue());
    }

    @Test
    void isolatedPerUserUnlessAdminAll() {
        Long a = user("tu_b");
        Long b = user("tu_c");
        Long adminId = user("tu_d");
        tokenUsageService.clearToday(a);
        tokenUsageService.clearToday(b);
        tokenUsageService.record(a, 10, 5);
        tokenUsageService.record(b, 7, 3);

        // 普通用户只见自己
        List<Map<String, Object>> mine = tokenUsageService.recent(b, false, false, 7);
        assertEquals(1, mine.size());
        assertEquals(10, ((Number) mine.get(0).get("totalTokens")).intValue());

        // ADMIN all 视角:两人都可见且带用户名
        List<Map<String, Object>> all = tokenUsageService.recent(adminId, true, true, 7);
        assertTrue(all.stream().anyMatch(r -> r.get("username") != null
                && String.valueOf(r.get("username")).startsWith("tu_b")), "全员视角应带用户名: " + all);
        assertTrue(all.stream().filter(r -> String.valueOf(r.get("username")).startsWith("tu_")).count() >= 2);
    }

    @Test
    void nullUsageCountsRequestOnly() {
        Long uid = user("tu_e");
        tokenUsageService.clearToday(uid);
        tokenUsageService.record(uid, null, null);
        List<Map<String, Object>> rows = tokenUsageService.recent(uid, false, false, 7);
        assertEquals(0, ((Number) rows.get(0).get("totalTokens")).intValue());
        assertEquals(1, ((Number) rows.get(0).get("requests")).intValue());
    }
}
