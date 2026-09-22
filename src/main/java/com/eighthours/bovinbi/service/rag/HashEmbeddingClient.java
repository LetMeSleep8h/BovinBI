package com.eighthours.bovinbi.service.rag;

/**
 * 进程内哈希嵌入(零依赖演示实现):
 * 字符 bigram + 单字哈希到固定维度后 L2 归一化。对中文短文本(字段业务名/同义词/问题)
 * 的相似度排序足够用 —— 共享字面片段越多,余弦越高;不联网、确定性、可单测。
 * 生产切换 OpenAiEmbeddingClient 只改 bovin.rag.embedding.provider,接口不变。
 * 装配统一走 RagConfig(按配置选择实现),本类不标 @Component 避免与 @Bean 工厂重复注册。
 */
public class HashEmbeddingClient implements EmbeddingClient {

    /** 向量维度:2^8 桶 + bigram 碰撞率对本场景(几十~几百个字段)足够 */
    private static final int DIM = 256;

    @Override
    public float[] embed(String text) {
        String t = text == null ? "" : text.toLowerCase().replaceAll("\\s+", "");
        float[] v = new float[DIM];
        for (int i = 0; i < t.length(); i++) {
            v[Math.floorMod(t.charAt(i), DIM)] += 0.5f;
            if (i + 1 < t.length()) {
                v[Math.floorMod(t.substring(i, i + 2).hashCode(), DIM)] += 1.0f;
            }
        }
        double norm = 0.0;
        for (float x : v) norm += (double) x * x;
        norm = Math.sqrt(norm);
        if (norm > 0) {
            for (int i = 0; i < DIM; i++) v[i] /= (float) norm;
        }
        return v;
    }
}
