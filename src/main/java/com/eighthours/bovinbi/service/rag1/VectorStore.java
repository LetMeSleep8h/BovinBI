package com.eighthours.bovinbi.service.rag1;

import java.util.List;

/**
 * 意图问例向量库抽象:rag1 的检索底座。
 * 唯一生产实现 PgVectorStore(PostgreSQL + pgvector 扩展,HNSW 余弦索引);
 * 单测用 mock,真实链路集成测试用 Testcontainers pgvector 容器。
 * 接口约定:写入的是 IntentExample 的向量,检索只返回意图+文本+相似度,不含向量本身。
 */
public interface VectorStore {

    /** 初始化(建表/建索引/探活);失败不抛异常,返回 false 由装配层决定降级 */
    boolean init();

    /** 幂等灌入标注问例(按 id upsert);库非空时跳过,避免每次重启重复写 */
    int seedIfEmpty(List<IntentExample> examples);

    /** 余弦相似度 topK 召回(分数越高越相似;实现需自行保证按分降序) */
    List<Match> search(float[] queryVector, int topK);

    /** 计数,供健康检查与日志 */
    long count();

    /** 单条召回结果:相似问例及其得分 */
    record Match(IntentLabel intent, String text, double score) {
    }
}
