package com.eighthours.bovinbi.service.rag;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 哈希嵌入确定性验证:同文=1、语义相近 > 语义无关 —— RAG 检索质量的最低契约。
 */
class HashEmbeddingClientTest {

    private final HashEmbeddingClient embedder = new HashEmbeddingClient();

    private double cos(String a, String b) {
        return EmbeddingClient.cosine(embedder.embed(a), embedder.embed(b));
    }

    @Test
    void sameTextIsExactlyOne() {
        // float 归一化的余弦存在 1e-7 级精度损失,断言到 1e-6
        assertEquals(1.0, cos("产奶量Top10牧场", "产奶量Top10牧场"), 1e-6);
    }

    @Test
    void similarChinesePhrasesRankAboveUnrelated() {
        double related = cos("近12个月每月产奶量趋势", "近6个月产奶量走势");
        double unrelated = cos("近12个月每月产奶量趋势", "品种胎次泌乳阶段牛舍");
        assertTrue(related > unrelated, "相近问法相似度应高于无关文本: related=" + related + " unrelated=" + unrelated);
    }

    @Test
    void emptyTextIsZeroVectorAndSafe() {
        assertEquals(0.0, cos("", "任何文本"));
        assertEquals(0.0, cos(null, null));
    }
}
