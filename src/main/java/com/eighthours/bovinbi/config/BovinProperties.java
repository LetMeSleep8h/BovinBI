package com.eighthours.bovinbi.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private Rag1 rag1 = new Rag1();
    private Gateway gateway = new Gateway();
    private Mcp mcp = new Mcp();

    @Data
    public static class Chat {
        /** 查询引擎:pipeline=固定管线(默认) | agent=单Agent工具循环 | multi-agent=三Agent流水线 | orchestra=多Agent编排(Supervisor路由+声明式工作流) */
        private String engine = "pipeline";
        private boolean fallbackToRule = true;
        private int maxRows = 1000;
        private int queryTimeoutSeconds = 8;
        private Agent agent = new Agent();
        private MultiAgent multiAgent = new MultiAgent();
        private Orchestra orchestra = new Orchestra();
    }

    /** multi-agent 引擎参数 */
    @Data
    public static class MultiAgent {
        /** 审查不过时的修复轮数(SQL Agent 1 次 + Repair Agent N 次) */
        private int maxRepairs = 2;
        /** 是否启用 LLM 语义审查(关闭则只跑确定性审查:守护/时间对账/LIMIT) */
        private boolean llmReview = true;
    }

    /** orchestra 引擎参数(多 Agent 编排:Supervisor 路由 + 声明式工作流) */
    @Data
    public static class Orchestra {
        /** 修复环轮数(首轮生成 + N 轮定向修复,同 multi-agent.max-repairs 语义) */
        private int maxRepairs = 2;
        /** 是否启用 LLM 语义审查(关闭则修复环只跑确定性审查,整步在编排中跳过) */
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

    /**
     * RAG1:意图识别增强(与 rag 的 Schema 召回互补,面向"问题级"而非"字段级")。
     * 标注问例向量化后入库,新问题向量召回 topK → 按意图投票 → 意图标签 + 置信度 + 相似问例;
     * 问例同时作为少样本注入各引擎 prompt,闲聊分流覆盖更多口语问法。
     */
    @Data
    public static class Rag1 {
        /** 开启后装配 Rag1IntentService;false 时无该 Bean,行为与旧版完全一致(可一键回滚) */
        private boolean enabled = false;
        /** pgvector 库 JDBC url(如 jdbc:postgresql://localhost:5432/bovinbi);留空 = 进程内向量库降级演示 */
        private String url = "";
        private String username = "postgres";
        private String password = "";
        /** 向量表名(需先 CREATE EXTENSION vector;建表建索引由 PgVectorStore 幂等完成) */
        private String table = "rag1_intent_example";
        /** 召回相似问例数 */
        private int topK = 3;
        /**
         * 判定意图的余弦相似度阈值,低于则 UNKNOWN(交给关键词兜底,不影响主链路)。
         * 校准依据(hash 嵌入实测):同义改写 0.65+,乱码/跨域噪声 <=0.45,取中间值;
         * openai 嵌入下语义相似更分离,0.55 两侧均适用。
         */
        private double threshold = 0.55;
        /** 建库连接超时毫秒:PG 不可达时快速失败切进程内兜底,不拖慢启动 */
        private int connectionTimeoutMs = 5000;
    }

    /**
     * MCP 工具统一管理:本地工具 + 远程 MCP server 的工具在 McpToolRegistry 合并成一份目录,
     * LangChain4j 工具循环与对外 /mcp 端点共用同一执行入口(预算/守护/轨迹同一套)。
     */
    @Data
    public static class Mcp {
        /** 暴露 POST /mcp(JSON-RPC)把工具集发布为 MCP server,供外部 MCP 客户端接入 */
        private boolean serverEnabled = false;
        /** /mcp 鉴权 key(Bearer);留空 = 演示不鉴权 */
        private Set<String> apiKeys = new LinkedHashSet<>();
        /** 远程 MCP server 列表;其工具以 "服务名__工具名" 进入统一目录 */
        private List<RemoteServer> servers = new ArrayList<>();

        @Data
        public static class RemoteServer {
            private String name;
            private String url;
            private String apiKey = "";
            private boolean enabled = true;
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
