package com.eighthours.bovinbi.controller;

import com.eighthours.bovinbi.common.ApiResponse;
import com.eighthours.bovinbi.security.UserContext;
import com.eighthours.bovinbi.service.TokenUsageService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * token 用量查询:数据隔离与审计日志同款边界 ——
 * 普通用户只见自己的每日用量;ADMIN 可带 all=true 看全员(成本核算视角)。
 */
@RestController
@RequestMapping("/api/usage")
@RequiredArgsConstructor
public class UsageController {

    private final TokenUsageService tokenUsageService;

    /** 近 N 天每日用量(?days=14;&all=true 仅 ADMIN 生效,查全员并带用户名) */
    @GetMapping("/tokens")
    public ApiResponse<List<Map<String, Object>>> tokens(@RequestParam(defaultValue = "14") int days,
                                                         @RequestParam(defaultValue = "false") boolean all) {
        long uid = UserContext.uid() == null ? -1 : UserContext.uid();
        boolean admin = UserContext.isAdmin();
        return ApiResponse.ok(tokenUsageService.recent(uid, admin, all && admin, Math.min(Math.max(days, 1), 90)));
    }

    /** 今日汇总(self 或 ADMIN&all=true 全员) */
    @GetMapping("/tokens/today")
    public ApiResponse<Map<String, Object>> today(@RequestParam(defaultValue = "false") boolean all) {
        long uid = UserContext.uid() == null ? -1 : UserContext.uid();
        boolean admin = UserContext.isAdmin();
        return ApiResponse.ok(tokenUsageService.today(uid, admin, all && admin));
    }
}
