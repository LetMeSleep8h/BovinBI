package com.eighthours.bovinbi.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.eighthours.bovinbi.common.ApiResponse;
import com.eighthours.bovinbi.entity.QueryLog;
import com.eighthours.bovinbi.mapper.QueryLogMapper;
import com.eighthours.bovinbi.security.UserContext;
import com.eighthours.bovinbi.service.SemanticCache;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 查询审计与运行状态。
 * 数据隔离:审计日志含用户业务问题(敏感),普通用户只看自己的记录;
 * ADMIN 全量可见(管理审计)。默认拒绝 —— 非管理员查询一律强制 user_id 过滤。
 */
@RestController
@RequestMapping("/api/history")
@RequiredArgsConstructor
public class HistoryController {

    private final QueryLogMapper queryLogMapper;
    private final SemanticCache semanticCache;

    @GetMapping("/queries")
    public ApiResponse<Page<QueryLog>> page(@RequestParam(defaultValue = "1") long page,
                                            @RequestParam(defaultValue = "15") long size) {
        return ApiResponse.ok(queryLogMapper.selectPage(
                new Page<>(page, size),
                new LambdaQueryWrapper<QueryLog>()
                        .eq(!UserContext.isAdmin(), QueryLog::getUserId, UserContext.uid())
                        .orderByDesc(QueryLog::getId)));
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        LambdaQueryWrapper<QueryLog> scope = new LambdaQueryWrapper<QueryLog>()
                .eq(!UserContext.isAdmin(), QueryLog::getUserId, UserContext.uid());
        Map<String, Object> m = new LinkedHashMap<>();
        long total = queryLogMapper.selectCount(scope);
        long success = queryLogMapper.selectCount(
                new LambdaQueryWrapper<QueryLog>()
                        .eq(!UserContext.isAdmin(), QueryLog::getUserId, UserContext.uid())
                        .eq(QueryLog::getStatus, "SUCCESS"));
        m.put("totalQueries", total);
        m.put("successQueries", success);
        m.put("successRate", total == 0 ? "0%" : String.format("%.1f%%", success * 100.0 / total));
        m.put("cacheHits", semanticCache.hitCount());
        m.put("cacheRequests", semanticCache.requestCount());
        m.put("scope", UserContext.isAdmin() ? "ALL" : "SELF");
        return ApiResponse.ok(m);
    }
}
