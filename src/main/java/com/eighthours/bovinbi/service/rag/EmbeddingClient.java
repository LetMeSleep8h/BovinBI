package com.eighthours.bovinbi.service.rag;

/**
 * 文本向量化抽象(RAG 的检索底座)。
 * 实现谱系(bovin.rag.embedding.provider 切换):
 * - hash:进程内字符 n-gram 哈希向量,零外部依赖 —— 演示/测试默认;
 * - openai:OpenAI 兼容 /embeddings 端点 —— 生产推荐,换供应商只改配置。
 */
public interface EmbeddingClient {

    /** 文本 → L2 归一化向量;调用方之间用 {@link #cosine} 比较相似度 */
    float[] embed(String text);

    /** 余弦相似度(约定向量均已 L2 归一化,退化为点积) */
    static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0.0;
        double dot = 0.0;
        for (int i = 0; i < a.length; i++) dot += (double) a[i] * b[i];
        return dot;
    }
}
