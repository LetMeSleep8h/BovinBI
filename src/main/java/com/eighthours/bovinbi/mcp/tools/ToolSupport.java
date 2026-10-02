package com.eighthours.bovinbi.mcp.tools;

import com.eighthours.bovinbi.agent.AgentRunContext;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * 工具公共支撑:参数读取 + datasetId/白名单双形态解析。
 * 工具有两种调用形态,语义一致、状态来源不同:
 * - Agent 循环内(ThreadLocal AgentRunContext 存在):datasetId 取上下文,
 *   白名单优先用 getSchema 已登记的(预算/登记/轨迹语义与原 BovinTools 完全一致);
 * - Agent 循环外(外部 MCP 客户端 / 编排直调):必须显式传 datasetId,
 *   白名单每次按数据集元数据现查 —— 安全边界不因入口不同而放宽。
 */
@Component
@RequiredArgsConstructor
public class ToolSupport {

    private final DatasetMapper datasetMapper;

    /** datasetId:显式参数优先;循环内取上下文;两者皆无 → null(调用方负责报错) */
    public Long resolveDatasetId(Map<String, Object> args, AgentRunContext ctx) {
        Long explicit = lng(args, "datasetId");
        if (explicit != null) {
            return explicit;
        }
        return ctx == null ? null : ctx.datasetId();
    }

    /** 白名单:循环内已登记优先(与 getSchema 的登记联动);否则按 datasetId 从元数据加载 */
    public Set<String> resolveWhitelist(Long datasetId, AgentRunContext ctx) {
        if (ctx != null && !ctx.whitelist().isEmpty()) {
            return ctx.whitelist();
        }
        Dataset ds = datasetId == null ? null : datasetMapper.selectById(datasetId);
        if (ds == null || ds.getDwhTables() == null || ds.getDwhTables().isBlank()) {
            throw new BizException(404, "数据集不存在或未配置表白名单: " + datasetId);
        }
        return new HashSet<>(Arrays.asList(ds.getDwhTables().toLowerCase().split("[,，\\s]+")));
    }

    public static String str(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static Long lng(Map<String, Object> args, String key) {
        Object v = args == null ? null : args.get(key);
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null || String.valueOf(v).isBlank() ? null : Long.parseLong(String.valueOf(v));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
