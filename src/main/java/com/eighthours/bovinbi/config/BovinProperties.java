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
        private boolean fallbackToRule = true;
        private int maxRows = 1000;
        private int queryTimeoutSeconds = 8;
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
