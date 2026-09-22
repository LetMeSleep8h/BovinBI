package com.eighthours.bovinbi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
@Component
@ConfigurationProperties(prefix = "bovin")
public class BovinProperties {
    private Chat chat = new Chat();
    private Cache cache = new Cache();
    private Security security = new Security();
    private Llm llm = new Llm();
    private Dwh dwh = new Dwh();
    private Rag rag = new Rag();
    private Gateway gateway = new Gateway();

    @Data
    public static class Chat {
        /** 查询引擎:pipeline=固定管线(默认) | agent=单Agent工具循环 | multi-agent=SQL/审查/修复三Agent流水线 */
        private String engine = "pipeline";
        private boolean fallbackToRule = true;
        private int maxRows = 1000;
        private int queryTimeoutSeconds = 8;
        private Agent agent = new Agent();
        private MultiAgent multiAgent = new MultiAgent();
    }

    /** multi-agent 引擎参数 */
    @Data
    public static class MultiAgent {
        /** 审查不过时的修复轮数(SQL Agent 1 次 + Repair Agent N 次) */
        private int maxRepairs = 2;
        /** 是否启用 LLM 语义审查(关闭则只跑确定性审查:守护/时间对账/LIMIT) */
        private boolean llmReview = true;
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
        /** Agent 循环内单次 LLM 调用超时(秒):独立于 bovin.llm.timeout-seconds ——
         *  工具循环一次问答含多次 LLM 往返,单次等待必须更短,失败才降级得快 */
        private int llmTimeoutSeconds = 30;
        /** Agent 循环内单次 LLM 调用的 HTTP 重试:0=不重试 —— 工具循环本身具备
         *  "报错回喂、模型自修"的语义,HTTP 层重试只会在多轮循环上叠加耗时 */
        private int llmMaxRetries = 0;
    }

    @Data
    public static class Cache {
        private long ttlSeconds = 600;
        private long maxSize = 512;
        /** 语义缓存二次命中(向量相似度):默认关闭,开启后精确 key 未命中时做相似度回退 */
        private boolean semanticEnabled = false;
        /** 语义二次命中的余弦相似度阈值 */
        private double semanticThreshold = 0.92;
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

    /** RAG:Schema 混合召回(词面打分 + 向量重排 + topK 截断) */
    @Data
    public static class Rag {
        /** 开启后 SchemaRetriever 切换为 HybridSchemaRetriever(@Primary);字段数<=topK 时行为与词面版一致 */
        private boolean enabled = false;
        /** 注入提示词的字段数上限(防全字段进 prompt 的 token 膨胀) */
        private int topK = 32;
        private Embedding embedding = new Embedding();

        @Data
        public static class Embedding {
            /** hash=进程内 n-gram 哈希向量(零依赖演示) / openai=OpenAI 兼容 /embeddings 端点 */
            private String provider = "hash";
            private String baseUrl = "";
            private String apiKey = "";
            private String model = "text-embedding-3-small";
        }
    }

    /** 轻量 LLM 网关:OpenAI 兼容入口,路由/failover/熔断/限流/计量/响应缓存 */
    @Data
    public static class Gateway {
        /** 开启后暴露 POST /gateway/v1/chat/completions;业务侧把 bovin.llm.base-url 指向它即可接入 */
        private boolean enabled = false;
        private long cacheTtlSeconds = 300;
        private int timeoutSeconds = 60;
        /** 连续失败 N 次后熔断该供应商 breaker-open-seconds 秒 */
        private int breakerThreshold = 3;
        private long breakerOpenSeconds = 60;
        /** 调用方 key -> 每分钟请求数;为空 = 不鉴权不限流(演示) */
        private Map<String, Integer> apiKeys = new LinkedHashMap<>();
        private List<Provider> providers = new ArrayList<>();

        @Data
        public static class Provider {
            private String name;
            private String baseUrl;
            private String apiKey;
            private String model;
            /** 数值越小越优先 */
            private int priority = 1;
            private boolean enabled = true;
        }
    }
}
