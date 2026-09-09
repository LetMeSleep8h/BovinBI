package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.llm.LlmClient;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

/** LLM 生成器单测:手写 FakeLlmClient 固化 生成/解析容错/自修复 的提示词契约 */
class LlmSqlGeneratorTest {

    /** 假 LLM:返回预置回复,记录收到的提示词,可注入异常 */
    private static class FakeLlmClient implements LlmClient {
        String reply = "{\"sql\": \"SELECT 1\", \"explanation\": \"ok\"}";
        String lastSystemPrompt;
        String lastUserPrompt;
        RuntimeException chatError;

        @Override
        public String chat(String systemPrompt, String userPrompt) {
            if (chatError != null) {
                throw chatError;
            }
            lastSystemPrompt = systemPrompt;
            lastUserPrompt = userPrompt;
            return reply;
        }

        /** 与 LangChain4jClient 同款容错:直接解析,失败则截取 {...} */
        @Override
        public JsonNode extractJson(String raw) {
            try {
                return new ObjectMapper().readTree(raw);
            } catch (Exception ignore) {
                Matcher m = Pattern.compile("\\{.*}", Pattern.DOTALL).matcher(raw);
                if (m.find()) {
                    try {
                        return new ObjectMapper().readTree(m.group());
                    } catch (Exception ignored) {
                    }
                }
                throw new BizException(502, "无法从模型回复中解析出 JSON");
            }
        }
    }

    private final FakeLlmClient fake = new FakeLlmClient();
    private final LlmSqlGenerator generator = new LlmSqlGenerator(fake);

    private SqlGenContext ctx() {
        return new SqlGenContext("今年总产奶量", null, "【数据集】牧场养殖分析",
                Set.of("dwh_fact_milk"));
    }

    @Test
    void generateParsesJson() {
        SqlResult r = generator.generate(ctx());
        assertEquals("SELECT 1", r.sql());
        assertEquals("ok", r.explanation());
    }

    @Test
    void generateToleratesMarkdownFence() {
        fake.reply = "```json\n{\"sql\": \"SELECT 2\", \"explanation\": \"fenced\"}\n```";
        assertEquals("SELECT 2", generator.generate(ctx()).sql());
    }

    @Test
    void generateIncludesSchemaAndSideInfo() {
        generator.generate(ctx());
        assertTrue(fake.lastUserPrompt.contains("【数据集】牧场养殖分析"));
        assertTrue(fake.lastUserPrompt.contains("#Question: 今年总产奶量"));
        assertTrue(fake.lastUserPrompt.contains("今天日期"));
        assertTrue(fake.lastSystemPrompt.contains("#Role"));
    }

    @Test
    void emptySqlThrows() {
        fake.reply = "{\"sql\": \"\", \"explanation\": \"empty\"}";
        assertThrows(BizException.class, () -> generator.generate(ctx()));
    }

    @Test
    void chatFailureThrowsBizException() {
        fake.chatError = new RuntimeException("connection refused");
        BizException e = assertThrows(BizException.class, () -> generator.generate(ctx()));
        assertTrue(e.getMessage().contains("LLM 生成 SQL 失败"));
    }

    @Test
    void repairCarriesFailedSqlAndError() {
        fake.reply = "{\"sql\": \"SELECT 9\", \"explanation\": \"fixed\"}";
        SqlResult r = generator.repair(ctx(), "SELECT 1 FROM wrong", "表不存在");
        assertEquals("SELECT 9", r.sql());
        assertTrue(fake.lastUserPrompt.contains("SELECT 1 FROM wrong"), "自修复提示词应携带失败 SQL");
        assertTrue(fake.lastUserPrompt.contains("表不存在"), "自修复提示词应携带数据库报错");
    }
}
