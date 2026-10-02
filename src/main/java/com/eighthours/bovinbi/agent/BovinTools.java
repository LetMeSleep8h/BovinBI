package com.eighthours.bovinbi.agent;

import com.eighthours.bovinbi.mcp.McpToolRegistry;
import dev.langchain4j.agent.tool.Tool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Agent 工具集 —— LangChain4j @Tool 形态的薄适配层:
 * 真实实现与安全边界(守护/白名单/预算/标识符校验)全部在 MCP 注册中心后面的
 * mcp.tools.* 工具里(GetSchemaTool / GetColumnValuesTool / ExecuteSqlTool);
 * 本类只做两件事:把工具暴露成 AiServices 可发现的 @Tool 方法、
 * 把调用转成注册中心统一入口 call(name, args)。
 *
 * 统一管理的收益:同一份工具,三个身份 —— ① Agent 循环的工具(本类);
 * ② 对外 MCP server 的 tools/call(POST /mcp);③ 远程 MCP server 工具的同目录邻居。
 * 预算扣减/轨迹记录/异常隔离在注册中心一处实现,三种入口行为完全一致。
 *
 * 三条设计原则不变(面试要点):
 * - 提示词是建议,工具是法律:能否执行由 SqlGuard 决定,不靠模型自觉;
 * - 错误即数据:守护拒绝/执行报错以 isError + 文本回喂模型,驱动自修复;
 * - 预算在注册中心强制:超限直接拒绝话术,框架循环天然有界。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BovinTools {

    private final McpToolRegistry registry;

    @Tool("获取数据集的表结构与业务字段说明(字段业务名/口径/聚合方式/同义词)。写任何 SQL 之前必须先调用本工具;可传入问题关键词提高字段召回排序。")
    public String getSchema(String keywords) {
        Map<String, Object> args = new HashMap<>();
        if (keywords != null) {
            args.put("keywords", keywords);
        }
        return registry.call("getSchema", args).text();
    }

    @Tool("查询某个维度字段在库中的真实可选值(如牧场名/品种/地区),用于生成准确的 WHERE 条件,防止编造维度值。table 与 column 必须来自 Schema。")
    public String getColumnValues(String tableName, String columnName, String keyword) {
        Map<String, Object> args = new HashMap<>();
        args.put("tableName", tableName);
        args.put("columnName", columnName);
        if (keyword != null) {
            args.put("keyword", keyword);
        }
        return registry.call("getColumnValues", args).text();
    }

    @Tool("执行一条只读 SELECT 查询并返回结果预览(列名+前若干行)。SQL 会经过安全守护:仅单条 SELECT/表白名单/强制 LIMIT。执行失败会返回报错原因,请阅读报错修正后重试(注意 SQL 执行次数有上限)。")
    public String executeSql(String sql) {
        Map<String, Object> args = new HashMap<>();
        args.put("sql", sql);
        return registry.call("executeSql", args).text();
    }

    /** 只读导出(注册中心里的 exportReport);批量导入是写操作,刻意不暴露给模型 —— Agent 循环保持只读 */
    @Tool("把一条查询结果导出为 CSV 报表文本(首行列名),权限与 executeSql 相同(守护/白名单/强制 LIMIT)。")
    public String exportReport(String sql) {
        Map<String, Object> args = new HashMap<>();
        args.put("sql", sql);
        return registry.call("exportReport", args).text();
    }
}
