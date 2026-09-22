package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.eighthours.bovinbi.service.rag.EmbeddingClient;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 语义缓存(演示版为归一化文本精确匹配 + 可选向量相似度二次命中):
 * key = 数据集 + 归一化问题 + 时间区间 → 完整回答载荷。
 * 面试要点:命中缓存可跳过 LLM 生成与 SQL 执行,让"重复问法"毫秒级返回;
 * 精确未命中时 getSemantic 以 embedding 余弦相似度回退(bovin.cache.semantic-enabled 开关),
 * 生产可升级为向量库 ANN 检索替代这里的全量遍历。
 */
@Service
public class SemanticCache {

    private final Cache<String, CacheEntry> cache;

    private record CacheEntry(AnswerPayload payload, long bornAt,
                              Long datasetId, String question, String timeSuffix) {
    }

    public SemanticCache(BovinProperties props) {
        this.cache = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofSeconds(props.getCache().getTtlSeconds()))
                .maximumSize(props.getCache().getMaxSize())
                .recordStats()
                .build();
    }

    public String key(Long datasetId, String question, TimeRange tr) {
        return datasetId + "|" + normalize(question) + "|" + (tr == null ? "ALL" : tr.cacheKey());
    }

    public AnswerPayload get(String key) {
        CacheEntry e = cache.getIfPresent(key);
        return e == null ? null : e.payload();
    }

    /**
     * 语义二次命中:同数据集 + 同时间前缀的条目里,找与问题余弦相似度 ≥ 阈值的载荷。
     * 相似问题("上个月产奶量" vs "上月奶量")精确 key 不同,但答案相同 —— 由此扩大命中率。
     */
    public AnswerPayload getSemantic(Long datasetId, String question, TimeRange tr,
                                     EmbeddingClient embedder, double threshold) {
        String suffix = tr == null ? "ALL" : tr.cacheKey();
        final float[] qv = embedder.embed(question == null ? "" : question);
        for (CacheEntry e : cache.asMap().values()) {
            if (e.datasetId() == null || !e.datasetId().equals(datasetId) || !suffix.equals(e.timeSuffix())) {
                continue;
            }
            double sim = EmbeddingClient.cosine(qv, embedder.embed(e.question()));
            if (sim >= threshold) {
                return e.payload();
            }
        }
        return null;
    }

    public void put(String key, AnswerPayload payload) {
        // 只缓存查询成功的结果
        if (payload != null && !payload.isFallback() && payload.getSql() != null) {
            String[] parts = key.split("\\|", 3);
            Long dsId = parts.length > 0 && parts[0].matches("\\d+") ? Long.parseLong(parts[0]) : null;
            String q = parts.length > 1 ? parts[1] : "";
            String suffix = parts.length > 2 ? parts[2] : "ALL";
            cache.put(key, new CacheEntry(payload, System.currentTimeMillis(), dsId, q, suffix));
        }
    }

    public long hitCount() {
        return cache.stats().hitCount();
    }

    public long requestCount() {
        return cache.stats().requestCount();
    }

    /** 归一化:忽略大小写、空白与标点差异("总销售额?"与"总 销售额!"同 key) */
    static String normalize(String q) {
        return q == null ? "" : q.toLowerCase().replaceAll("[\\p{Punct}\\p{IsPunctuation}\\s?？!！。,,;;:：]+", "");
    }
}
