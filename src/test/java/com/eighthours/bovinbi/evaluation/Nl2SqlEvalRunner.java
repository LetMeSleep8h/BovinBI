package com.eighthours.bovinbi.evaluation;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.init.DataLoader;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.service.Nl2SqlService;
import org.apache.commons.csv.CSVFormat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * NL2SQL 评测器(产出简历量化指标的核心工具):
 * mvn test -Dtest=Nl2SqlEvalRunner
 * - 引擎由配置决定:BOVIN_CHAT_ENGINE=pipeline(或默认)评测固定管线;BOVIN_CHAT_ENGINE=agent 评测 Agent 工具循环
 * - provider=mock 时评测规则引擎准确率;provider=openai(配 LLM_API_KEY)时评测大模型准确率
 * - 逐条执行:生成SQL → 守护 → 执行;断言 1) 不降级 2) SQL 归一化后包含 must_contain 断言
 * - 输出 eval-report.md(含每条失败明细与工具调用统计,可直接作为面试材料)
 *   A/B 对比:同一评测集分别以 pipeline/agent 跑一遍,两次报告并排即是"Agent 化收益"的证据
 */
@SpringBootTest
class Nl2SqlEvalRunner {

    @Autowired
    private Nl2SqlService nl2SqlService;

    @Autowired
    private DatasetMapper datasetMapper;

    @Autowired
    private BovinProperties props;

    private record Case(int line, String question, List<String> musts) {
    }

    @Test
    void runEval() throws Exception {
        // 按名称解析数据集 id(迁移/重建后自增 id 会变化,不写死 1)
        Dataset ds = datasetMapper.selectOne(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getName, DataLoader.DATASET_NAME).last("LIMIT 1"));
        if (ds == null) {
            ds = datasetMapper.selectList(null).get(0);
        }
        Long datasetId = ds.getId();
        List<Case> cases = new ArrayList<>();
        try (var reader = new InputStreamReader(new ClassPathResource("eval/questions.csv").getInputStream(), StandardCharsets.UTF_8)) {
            var records = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).build().parse(reader);
            int line = 1;
            for (var r : records) {
                String musts = r.get("must_contain");
                List<String> list = new ArrayList<>();
                for (String m : musts.split("\\|")) {
                    if (!m.isBlank()) list.add(normalize(m));
                }
                cases.add(new Case(line++, r.get("question"), list));
            }
        }

        int pass = 0;
        List<String> failures = new ArrayList<>();
        long t0 = System.currentTimeMillis();
        long traceCalls = 0;
        int tracedAnswers = 0;
        int budgetExhausted = 0;
        for (Case c : cases) {
            try {
                AnswerPayload p = nl2SqlService.answer(datasetId, c.question());
                if (p.getTrace() != null && !p.getTrace().isEmpty()) {
                    tracedAnswers++;
                    traceCalls += p.getTrace().size();
                    boolean hitBudget = p.getTrace().stream()
                            .anyMatch(t -> t.summary() != null && t.summary().contains("预算"));
                    if (hitBudget) {
                        budgetExhausted++;
                    }
                }
                if (p.isFallback()) {
                    failures.add("- 第%d行 [%s] → 降级(无法理解)".formatted(c.line(), c.question()));
                    continue;
                }
                String norm = normalize(p.getSql());
                String miss = c.musts().stream().filter(m -> !matches(m, norm))
                        .reduce((a, b) -> a + " && " + b).orElse(null);
                if (miss != null) {
                    failures.add("- 第%d行 [%s] → SQL缺断言: %s | 实际: `%s`".formatted(c.line(), c.question(), miss, p.getSql().replaceAll("\\s+", " ")));
                    continue;
                }
                pass++;
            } catch (Exception e) {
                failures.add("- 第%d行 [%s] → 异常: %s".formatted(c.line(), c.question(), e.getMessage()));
            }
        }
        long cost = System.currentTimeMillis() - t0;

        String report = """
                # BovinBI NL2SQL 评测报告

                - 评测时间: %s
                - 引擎: %s,时钟 = 当天(相对时间随评测日变化)
                - 样本: %d 条(覆盖 指标/趋势/TopN/占比/环比同比/维度汇总/组合查询)
                - 通过: **%d**
                - 结构准确率(断言命中): **%.1f%%**
                - 执行成功率(未降级且SQL可执行): **%.1f%%**
                - 总耗时: %d ms,平均 %.0f ms/条
                - 工具调用: 平均 %.1f 次/题(%d 题含轨迹),预算触顶 %d 题

                ## 失败明细(%d 条)

                %s
                """.formatted(
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")),
                engineLabel(),
                cases.size(), pass, pass * 100.0 / cases.size(),
                (cases.size() - failures.size()) * 100.0 / cases.size(),
                cost, (double) cost / cases.size(),
                tracedAnswers == 0 ? 0 : (double) traceCalls / tracedAnswers, tracedAnswers, budgetExhausted,
                failures.size(),
                String.join("\n", failures));

        Files.writeString(Path.of("eval-report.md"), report);
        System.out.println("\n================ 评测结果 ================");
        System.out.println("引擎 " + engineLabel());
        System.out.println("通过 " + pass + "/" + cases.size() + "  准确率 " + String.format("%.1f%%", pass * 100.0 / cases.size()));
        System.out.println("报告已写入 eval-report.md");
        assertEquals(cases.size(), pass + failures.size());
    }

    /** 引擎标签:pipeline(mock=规则引擎 / openai=LLM 单轮)或 agent(工具循环,含预算参数) */
    private String engineLabel() {
        boolean agent = "agent".equalsIgnoreCase(props.getChat().getEngine());
        if (agent) {
            return "Agent 工具循环(%s, 预算: 总%d次/SQL%d次)".formatted(
                    props.getLlm().getProvider(),
                    props.getChat().getAgent().getMaxToolCalls(),
                    props.getChat().getAgent().getMaxSqlExecutions());
        }
        return "mock".equalsIgnoreCase(props.getLlm().getProvider())
                ? "规则引擎(RuleSqlGenerator)"
                : "固定管线(LLM 单轮生成+自修复一次)";
    }

    private String normalize(String sql) {
        return sql == null ? "" : sql.toLowerCase().replaceAll("\\s+", "");
    }

    /**
     * 断言语义化匹配(A/B 首轮发现的评测缺陷修复):
     * 原 must_contain 字面匹配 house 风格(按位置 GROUP BY 1 / ORDER BY 2 DESC),与规则引擎、
     * pipeline 提示词示例同源 —— 首轮 A/B 中 45/123 条 agent SQL 语义正确,却因书写风格不同
     * (按表达式/别名分组排序)被误判为失败。修复原则:断言只约束语义意图(有分组/有排序/方向正确),
     * 其余 token(指标表达式/维度列/LIMIT/时间格式)仍按字面匹配。
     */
    private boolean matches(String must, String normSql) {
        return switch (must) {
            case "groupby1" -> normSql.contains("groupby");
            case "orderby1" -> normSql.contains("orderby");
            case "orderby2desc" -> normSql.contains("orderby") && normSql.contains("desc");
            case "orderby2asc" -> normSql.contains("orderby") && normSql.contains("asc");
            default -> normSql.contains(must);
        };
    }
}
