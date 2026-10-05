package com.eighthours.bovinbi.service.rag1;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.service.ChitChatHandler;
import com.eighthours.bovinbi.service.rag.EmbeddingClient;
import com.eighthours.bovinbi.llm.LlmClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * rag1 装配(pgvector 为唯一向量库):
 * - bovin.rag1.enabled=true 时要求 bovin.rag1.url 指向装了 vector 扩展的 PostgreSQL;
 * - PG 不可达/扩展缺失 → 打 WARN 后不装配该 Bean(意图识别增强整体缺席,
 *   闲聊分流自动回落关键词版)—— 主链路不因向量库故障中断,但也不静默降级:
 *   日志必须能看出 rag1 没在跑;
 * - 向量化复用 rag 的 EmbeddingClient 装配(hash/openai),两边共享同一嵌入模型。
 */
@Slf4j
@Configuration
public class Rag1Config {

    @Bean
    @ConditionalOnProperty(name = "bovin.rag1.enabled", havingValue = "true")
    public Rag1IntentService rag1IntentService(BovinProperties props, EmbeddingClient embedder,
                                               ChitChatHandler chitChatHandler,
                                               org.springframework.beans.factory.ObjectProvider<LlmClient> llm) {
        BovinProperties.Rag1 cfg = props.getRag1();
        if (cfg.getUrl() == null || cfg.getUrl().isBlank()) {
            log.warn("rag1 已启用但未配置 bovin.rag1.url(pgvector),意图识别增强不生效(闲聊回落关键词版)");
            return null;
        }
        PgVectorStore pg = new PgVectorStore(cfg, embedder);
        if (!pg.init()) {
            log.warn("rag1 pgvector 初始化失败(url={}),意图识别增强不生效(闲聊回落关键词版)", cfg.getUrl());
            return null;
        }
        pg.seedIfEmpty(IntentSeeds.load());
        // provider=openai 时注入 LlmClient:意图判定升级为 LLM 判别(离线自动退回向量召回)
        return new Rag1IntentService(pg, embedder, chitChatHandler, cfg,
                "openai".equalsIgnoreCase(props.getLlm().getProvider()) ? llm.getIfAvailable() : null);
    }
}
