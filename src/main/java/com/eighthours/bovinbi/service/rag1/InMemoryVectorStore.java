package com.eighthours.bovinbi.service.rag1;

import com.eighthours.bovinbi.service.rag.EmbeddingClient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 进程内向量库(PgVectorStore 的零依赖降级实现):
 * 暴力余弦扫描全部问例 —— 语料几十条时性能无感,演示/测试/PG 故障兜底三合一。
 * 与 PgVectorStore 行为同构(同样的 seedIfEmpty/search 契约),切换只改装配不改调用方。
 */
public class InMemoryVectorStore implements VectorStore {

    private record Entry(IntentExample example, float[] vector) {
    }

    private final EmbeddingClient embedder;
    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    public InMemoryVectorStore(EmbeddingClient embedder) {
        this.embedder = embedder;
    }

    @Override
    public boolean init() {
        return true; // 进程内无外部资源,永远健康
    }

    @Override
    public int seedIfEmpty(List<IntentExample> examples) {
        if (!entries.isEmpty()) {
            return 0;
        }
        examples.forEach(e -> entries.add(new Entry(e, embedder.embed(e.text()))));
        return entries.size();
    }

    @Override
    public List<Match> search(float[] queryVector, int topK) {
        List<Match> scored = new ArrayList<>(entries.size());
        for (Entry e : entries) {
            scored.add(new Match(e.example().intent(), e.example().text(),
                    EmbeddingClient.cosine(e.vector(), queryVector)));
        }
        scored.sort(Comparator.comparingDouble(Match::score).reversed());
        return scored.stream().limit(Math.max(1, topK)).toList();
    }

    @Override
    public long count() {
        return entries.size();
    }
}
