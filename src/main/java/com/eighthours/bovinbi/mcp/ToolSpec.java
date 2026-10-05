package com.eighthours.bovinbi.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具完整定义(ToolSpec):名称/描述/参数 schema/预算配额/是否写操作一次注册,三处共用 ——
 * ① LangChain4j Agent 循环的工具签名(经注册中心 call 分发)
 * ② 对外 MCP server 的 tools/list 目录
 * ③ 计划校验器(PlanPlanner 产出的计划按此校验工具存在性与参数)
 * 与参考实现的差异:每工具预算配额挂在元数据上(它没有;预算治理是我们的差异化),
 * mutator 标记写操作(STEP 审批/离线规划据此拒绝写)。
 */
public record ToolSpec(String name, String description, Map<String, Object> inputSchema,
                       int quota, boolean mutator) {

    /** 预算配额:循环内最多调用次数(quota<=0 表示不计预算) */
    public static ToolSpec of(String name, String description, Map<String, Object> schema,
                              int quota, boolean mutator) {
        Map<String, Object> normalized = new LinkedHashMap<>();
        schema.forEach((k, v) -> {
            String key = switch (k) {
                case "datasetId" -> "datasetId";
                default -> k;
            };
            normalized.put(key, v);
        });
        return new ToolSpec(name, description, normalized, quota, mutator);
    }
}
