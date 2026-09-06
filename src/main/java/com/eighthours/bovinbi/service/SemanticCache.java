package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.AnswerPayload;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 语义缓存(演示版为归一化文本精确匹配):
 * key = 数据集 + 归一化问题 + 时间区间 → 完整回答载荷。
 * 面试要点:命中缓存可跳过 LLM 生成与 SQL 执行,让"重复问法"毫秒级返回;
 * 生产可升级为"问题向量相似度"检索,扩大命中率。
 */
@Service
public class SemanticCache {

    private final Cache<String, AnswerPayload> cache;

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
        return cache.getIfPresent(key);
    }

    public void put(String key, AnswerPayload payload) {
        // 只缓存查询成功的结果
        if (payload != null && !payload.isFallback() && payload.getSql() != null) {
            cache.put(key, payload);
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
