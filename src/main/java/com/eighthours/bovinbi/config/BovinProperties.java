package com.eighthours.bovinbi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "bovin")
public class BovinProperties {
    private Chat chat = new Chat();
    private Cache cache = new Cache();
    private Security security = new Security();
    private Llm llm = new Llm();
    private Dwh dwh = new Dwh();

    @Data
    public static class Chat {
        /** 查询引擎:pipeline=固定管线(默认,行为与旧版一致) | agent=工具调用循环(Agent 模式,需 provider=openai) */
        private String engine = "pipeline";
        private boolean fallbackToRule = true;
        private int maxRows = 1000;
        private int queryTimeoutSeconds = 8;
        private Agent agent = new Agent();
    }

    /**
     * Agent 模式运行预算:自主性的边界全部在工具层强制。
     * 面试要点:SQL 执行预算是旧管线"自修复一次"的泛化 —— 修复重试从硬编码 1 次变成
     * 可配置、可统计的 N 次,取值由评测数据(命中率/成本曲线)决定,而非拍脑袋。
     */
    @Data
    public static class Agent {
        /** 工具调用总预算(所有工具合计),框架循环因此天然有界 */
        private int maxToolCalls = 12;
        /** SQL 执行预算(含失败重试) */
        private int maxSqlExecutions = 3;
        private int maxSchemaCalls = 2;
        private int maxValueCalls = 4;
        /** 结果预览行数(控制回喂模型的 token) */
        private int previewRows = 8;
        /** 会话记忆滑动窗口条数 */
        private int memoryMessages = 20;
    }

    @Data
    public static class Cache {
        private long ttlSeconds = 600;
        private long maxSize = 512;
    }

    @Data
    public static class Security {
        private String jwtSecret = "bovinbi-dev-secret-key";
        private long jwtTtlHours = 24;
    }

    @Data
    public static class Llm {
        private String provider = "mock";   // mock / openai(OpenAI 兼容协议)
        private String baseUrl = "https://api.deepseek.com";
        private String apiKey = "";
        private String model = "deepseek-chat";
        private double temperature = 0.0;
        private int timeoutSeconds = 40;
        private int maxRetries = 2;
        private boolean logRequests = false;
        private boolean logResponses = false;
    }

    @Data
    public static class Dwh {
        private String url = "";
        private String username = "";
        private String password = "";
    }
}
