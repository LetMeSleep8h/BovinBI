package com.eighthours.bovinbi.service.rag1;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.service.rag.HashEmbeddingClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PgVectorStore 真实链路集成测试(Testcontainers pgvector 容器,镜像自带 vector 扩展):
 * 建扩展/建表/建 HNSW 索引 → 空库灌语料 → 余弦召回 → 幂等(重复灌入跳过)。
 * 覆盖生产路径的全部 SQL:<=> 距离、::vector 字面量强转、ON CONFLICT upsert。
 */
class PgVectorStoreIntegrationTest {

    private static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("pgvector/pgvector:pg17")
            .withDatabaseName("bovin_rag")
            .withUsername("postgres")
            .withPassword("bovin123");

    private static PgVectorStore store;

    @BeforeAll
    static void setUp() {
        PG.start();
        BovinProperties.Rag1 cfg = new BovinProperties.Rag1();
        cfg.setUrl(PG.getJdbcUrl());
        cfg.setUsername(PG.getUsername());
        cfg.setPassword(PG.getPassword());
        store = new PgVectorStore(cfg, new HashEmbeddingClient());
        assertTrue(store.init(), "容器内建扩展/建表应成功");
        assertEquals(IntentSeeds.load().size(), store.seedIfEmpty(IntentSeeds.load()), "空库应全量灌入");
    }

    @AfterAll
    static void tearDown() {
        PG.stop();
    }

    @Test
    void searchReturnsRankedNeighbors() {
        // 相似问召回:查询与语料中趋势类问例最相似 → TREND 意图居首
        List<VectorStore.Match> hits = store.search(
                new HashEmbeddingClient().embed("近12个月每月产奶量趋势"), 3);
        assertEquals(3, hits.size());
        assertEquals(IntentLabel.TREND, hits.get(0).intent(), "最相似问例应为 TREND: " + hits);
        assertTrue(hits.get(0).score() > 0.9, "语料原句自查询应接近 1.0,实际 " + hits.get(0).score());
        // 召回按相似度降序
        assertTrue(hits.get(0).score() >= hits.get(1).score());
        assertEquals(IntentSeeds.load().size(), store.count());
    }

    @Test
    void seedIfEmptyIsIdempotent() {
        assertEquals(0, store.seedIfEmpty(IntentSeeds.load()), "已有数据不得重复灌入");
        assertEquals(IntentSeeds.load().size(), store.count());
    }
}
