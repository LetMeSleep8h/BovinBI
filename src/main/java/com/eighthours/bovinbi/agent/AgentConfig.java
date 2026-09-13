package com.eighthours.bovinbi.agent;

import com.eighthours.bovinbi.config.BovinProperties;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Agent 装配:仅 provider=openai 时创建 BovinAgent(AiServices 动态代理)。
 * 面试要点:
 * - 工具循环由 AiServices 内部驱动:发送消息+工具规格 → 模型返回工具调用 → 执行 @Tool 方法 →
 *   结果回喂 → 循环直至最终回答;应用侧只声明接口与工具,不手写循环;
 * - 会话记忆:memoryId=会话 id,滑动窗口 20 条(系统提示词由 provider 每轮注入,不占窗口);
 *   演示版为内存记忆、重启即失,生产可扩展 ChatMemoryStore 落库实现(见 docs/06 学习报告);
 * - 独立构建模型实例:与单轮生成的 LangChain4jClient 互不影响,tool-calling 参数互不串扰。
 */
@Slf4j
@Configuration
public class AgentConfig {

    private static final String SYSTEM_PROMPT = loadSystemPrompt();

    @Bean
    @ConditionalOnProperty(name = "bovin.llm.provider", havingValue = "openai")
    public BovinAgent bovinAgent(BovinProperties props, BovinTools bovinTools) {
        BovinProperties.Llm c = props.getLlm();
        ChatLanguageModel model = OpenAiChatModel.builder()
                .baseUrl(c.getBaseUrl())
                .apiKey(c.getApiKey())
                .modelName(c.getModel())
                .temperature(c.getTemperature())
                .maxRetries(c.getMaxRetries())
                .timeout(Duration.ofSeconds(c.getTimeoutSeconds()))
                .logRequests(c.isLogRequests())
                .logResponses(c.isLogResponses())
                .build();
        log.info("BovinAgent 已装配: model={}, 工具预算: 总{}次/SQL{}次/记忆窗口{}条",
                c.getModel(),
                props.getChat().getAgent().getMaxToolCalls(),
                props.getChat().getAgent().getMaxSqlExecutions(),
                props.getChat().getAgent().getMemoryMessages());
        return AiServices.builder(BovinAgent.class)
                .chatLanguageModel(model)
                .systemMessageProvider(memoryId -> SYSTEM_PROMPT)
                .chatMemoryProvider(memoryId -> MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(props.getChat().getAgent().getMemoryMessages())
                        .build())
                .tools(bovinTools)
                .build();
    }

    private static String loadSystemPrompt() {
        try (InputStream in = new ClassPathResource("prompts/agent-system.md").getInputStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("加载 prompts/agent-system.md 失败", e);
        }
    }
}
