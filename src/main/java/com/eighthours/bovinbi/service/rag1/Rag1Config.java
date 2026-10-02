package com.eighthours.bovinbi.service.rag1;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.service.ChitChatHandler;
import com.eighthours.bovinbi.service.rag.EmbeddingClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * rag1 装配(bovin.rag1.enabled=true 才有 Bean,关闭即整体消失,回滚零残留):
 * - 配了 url → PgVectorStore(pgvector + HNSW);初始化失败自动落到进程内向量库;
 * - 未配 url → 直接进程内向量库(零安装演示 rag1 全部行为);
 * - 向量化复用 rag 的 EmbeddingClient 装配(hash/openai),两边共享同一嵌入模型。
 */
@Slf4j
@Configuration
public class Rag1Config {

    @Bean
    @ConditionalOnProperty(name = "bovin.rag1.enabled", havingValue = "true")
    public Rag1IntentService rag1IntentService(BovinProperties props, EmbeddingClient embedder,
                                               ChitChatHandler chitChatHandler) {
        BovinProperties.Rag1 cfg = props.getRag1();
        List<IntentExample> seeds = IntentSeeds.load();
        VectorStore store;
        if (cfg.getUrl() != null && !cfg.getUrl().isBlank()) {
            PgVectorStore pg = new PgVectorStore(cfg, embedder);
            if (pg.init()) {
                pg.seedIfEmpty(seeds);
                store = pg;
            } else {
                store = inMemory(embedder, seeds);
            }
        } else {
            log.info("rag1 未配置 pgvector url,使用进程内向量库(演示模式,语料 {} 条)", seeds.size());
            store = inMemory(embedder, seeds);
        }
        return new Rag1IntentService(store, embedder, chitChatHandler, cfg);
    }

    private InMemoryVectorStore inMemory(EmbeddingClient embedder, List<IntentExample> seeds) {
        InMemoryVectorStore mem = new InMemoryVectorStore(embedder);
        mem.init();
        mem.seedIfEmpty(seeds);
        return mem;
    }
}
