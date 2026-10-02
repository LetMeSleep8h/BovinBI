package com.eighthours.bovinbi.service.rag1;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.service.rag.HashEmbeddingClient;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PgVectorStore 纯逻辑单测(不连库):向量字面量序列化 + 不可达降级契约。
 * 真实 pgvector 链路(建表/灌入/召回)属环境集成,依赖装了 vector 扩展的 PG,
 * 由 sql/pgvector-schema.sql + 手工验证;这里固化的是"PG 不可达绝不让主链路失败"。
 */
class PgVectorStoreTest {

    @Test
    void vectorLiteralSerializesToPgvectorTextFormat() {
        float[] v = {0.5f, -1.25f, 0.0f, 3.0f};
        assertEquals("[0.5,-1.25,0.0,3.0]", PgVectorStore.literal(v));
        assertEquals("[1.0]", PgVectorStore.literal(new float[]{1.0f}));
    }

    @Test
    void unreachableDatabaseDegradesToUnhealthyInsteadOfThrowing() {
        BovinProperties.Rag1 cfg = new BovinProperties.Rag1();
        cfg.setUrl("jdbc:postgresql://127.0.0.1:1/none"); // 保留地址必连不上(端口 1 无服务)
        cfg.setConnectionTimeoutMs(300);
        PgVectorStore store = new PgVectorStore(cfg, new HashEmbeddingClient());
        assertFalse(store.init(), "连不上应返回不健康,而非抛异常");
        assertFalse(store.healthy());
        assertEquals(0, store.count());
        assertTrue(store.search(new float[]{0.1f, 0.2f}, 3).isEmpty(), "不健康时检索返回空,不抛异常");
        assertEquals(0, store.seedIfEmpty(IntentSeeds.load()), "不健康时灌入为无操作");
    }
}
