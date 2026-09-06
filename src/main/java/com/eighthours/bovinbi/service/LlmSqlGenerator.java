package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.llm.LlmClient;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * LLM 版 SQL 生成器:单轮生成 + 一次纠错重试(self-repair)。
 * 对齐 SuperSonic 的 OnePassSCSqlGenStrategy:
 * - 提示词结构 #Role/#Task/#Rules/#Exemplars(系统模板) + #Schema/#SideInfo/#Question(变量化);
 * - 用 LangChain4j PromptTemplate 做变量填充(对应其 PromptTemplate.from(...).apply(variable));
 * - SideInfo 携带已解析的时间区间与今天日期(对应其 buildSideInformation)。
 */
@Slf4j
@Component
public class LlmSqlGenerator implements SqlGenerator {

    /**
     * 变量化查询模板(对齐 SuperSonic INSTRUCTION 的 #Query 段:
     * Question:{{question}},Schema:{{schema}},SideInfo:{{information}})
     */
    private static final String USER_TEMPLATE = """
            #Exemplars: {{exemplar}}
            #Schema: {{schema}}
            #SideInfo: {{side_info}}
            #Question: {{question}}""";

    private final LlmClient llmClient;
    private final String systemTemplate;
    private final PromptTemplate userTemplate;

    public LlmSqlGenerator(LlmClient llmClient) {
        this.llmClient = llmClient;
        this.userTemplate = PromptTemplate.from(USER_TEMPLATE);
        try {
            this.systemTemplate = new String(new ClassPathResource("prompts/nl2sql-system.md")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("加载提示词模板失败", e);
        }
    }

    @Override
    public SqlResult generate(SqlGenContext ctx) {
        String userPrompt = buildUserPrompt(ctx, null, null);
        for (int attempt = 1; attempt <= 2; attempt++) {
            try {
                String raw = llmClient.chat(systemTemplate, userPrompt);
                var json = llmClient.extractJson(raw);
                String sql = json.path("sql").asText("").trim();
                if (sql.isEmpty()) {
                    throw new BizException("模型未返回 SQL");
                }
                return new SqlResult(sql, json.path("explanation").asText(""), "LLM");
            } catch (Exception e) {
                log.warn("LLM 生成第 {} 次失败: {}", attempt, e.getMessage());
                if (attempt == 2) {
                    throw new BizException(502, "LLM 生成 SQL 失败: " + e.getMessage());
                }
                userPrompt = userPrompt + "\n\n上一次输出无法解析(" + e.getMessage() + "),请只输出一个合法 JSON 对象。";
            }
        }
        throw new BizException(502, "LLM 生成 SQL 失败");
    }

    /** 执行失败后的自修复:把失败 SQL 与数据库错误反馈给模型,重写一次 */
    public SqlResult repair(SqlGenContext ctx, String failedSql, String dbError) {
        String userPrompt = buildUserPrompt(ctx, failedSql, dbError);
        String raw = llmClient.chat(systemTemplate, userPrompt);
        var json = llmClient.extractJson(raw);
        String sql = json.path("sql").asText("").trim();
        if (sql.isEmpty()) {
            throw new BizException("模型未返回修正后的 SQL");
        }
        return new SqlResult(sql, json.path("explanation").asText("") + "(SQL 已经过自动修复)", "LLM(修复)");
    }

    /** 变量化组装用户提示:exemplar 预留动态少样本位,schema/side_info/question 为必填 */
    private String buildUserPrompt(SqlGenContext ctx, String failedSql, String dbError) {
        String sideInfo = "今天日期: " + LocalDate.now()
                + ";时间理解: " + (ctx.timeRange() == null
                ? "未识别到明确时间,默认不添加时间过滤"
                : "已解析为区间 [" + ctx.timeRange().start() + ", " + ctx.timeRange().endExclusive()
                + ") 标签:" + ctx.timeRange().label());
        Map<String, Object> vars = new HashMap<>();
        vars.put("exemplar", "");
        vars.put("schema", ctx.schema().schemaText());
        vars.put("side_info", sideInfo);
        vars.put("question", ctx.question());
        Prompt prompt = userTemplate.apply(vars);
        if (failedSql == null) {
            return prompt.text();
        }
        return prompt.text() + """

                ### 你上次生成的 SQL(执行失败)
                ```sql
                %s
                ```

                ### 数据库报错
                %s

                请修正这条 SQL(注意:聚合条件要放 HAVING 而不是 WHERE;只使用 Schema 中的表和列),严格按照系统约束输出 JSON。""".formatted(failedSql, dbError);
    }
}
