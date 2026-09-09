package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.llm.LlmClient;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

/**
 * LLM 版 SQL 生成器:单轮生成 + 独立的自修复入口(repair)。
 * - 提示词结构 #Role/#Task/#Rules/#Exemplars(系统模板) + #Schema/#SideInfo/#Question(变量化);
 * - 用 LangChain4j PromptTemplate 做变量填充;
 * - SideInfo 携带已解析的时间区间与今天日期,时间理解不依赖 LLM;
 * - 生成失败直接抛出,由 Nl2SqlService 的降级链接管(自修复 → 规则兜底),本类不自带重试。
 */
@Component
public class LlmSqlGenerator {

    /** 变量化查询模板:紧凑 Schema → 时间侧信息 → 用户问题 */
    private static final String USER_TEMPLATE = """
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

    public SqlResult generate(SqlGenContext ctx) {
        try {
            String raw = llmClient.chat(systemTemplate, buildUserPrompt(ctx, null, null));
            return parse(raw);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(502, "LLM 生成 SQL 失败: " + e.getMessage());
        }
    }

    /** 执行失败后的自修复:把失败 SQL 与数据库错误反馈给模型,重写一次 */
    public SqlResult repair(SqlGenContext ctx, String failedSql, String dbError) {
        String raw = llmClient.chat(systemTemplate, buildUserPrompt(ctx, failedSql, dbError));
        return parse(raw);
    }

    /** 解析模型回复:容忍 markdown 代码块包裹,sql 为空视为失败 */
    private SqlResult parse(String raw) {
        var json = llmClient.extractJson(raw);
        String sql = json.path("sql").asText("").trim();
        if (sql.isEmpty()) {
            throw new BizException(502, "模型未返回 SQL");
        }
        return new SqlResult(sql, json.path("explanation").asText(""));
    }

    /** 变量化组装用户提示:failedSql/dbError 非空时附加自修复段 */
    private String buildUserPrompt(SqlGenContext ctx, String failedSql, String dbError) {
        String sideInfo = "今天日期: " + LocalDate.now()
                + ";时间理解: " + (ctx.timeRange() == null
                ? "未识别到明确时间,默认不添加时间过滤"
                : "已解析为区间 [" + ctx.timeRange().start() + ", " + ctx.timeRange().endExclusive()
                + ") 标签:" + ctx.timeRange().label());
        Map<String, Object> vars = new HashMap<>();
        vars.put("schema", ctx.schemaText());
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
