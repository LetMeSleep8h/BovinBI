package com.eighthours.bovinbi.service.rag1;

import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.service.rag.EmbeddingClient;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

/**
 * pgvector 向量库(生产实现):意图问例存 PostgreSQL,余弦召回走 HNSW 索引。
 * - 建库幂等:CREATE EXTENSION vector → 建表(embedding vector(dim))→ 建 HNSW 索引;
 *   dim 取自装配的 EmbeddingClient(hash=256 / openai text-embedding-3-small=1536),
 *   换嵌入模型必须重建表(维度不符 PG 会直接报错,不会静默错配);
 * - 向量以文本字面量 '[0.1,0.2,...]' 传参再 ::vector 强转 —— 不引 pgvector JDBC 插件,
 *   驱动只依赖官方 postgresql;
 * - 失败降级不抛异常:init/检索任何 SQLException 都只记日志并标记不健康,
 *   由装配层(Rag1Config)切换 InMemoryVectorStore,主链路永不因向量库故障中断。
 */
@Slf4j
public class PgVectorStore implements VectorStore {

    private final BovinProperties.Rag1 cfg;
    private final EmbeddingClient embedder;
    private final int dim;

    private HikariDataSource pool;
    private volatile boolean healthy = false;

    public PgVectorStore(BovinProperties.Rag1 cfg, EmbeddingClient embedder) {
        this.cfg = cfg;
        this.embedder = embedder;
        this.dim = embedder.embed("dim-probe").length;
    }

    @Override
    public synchronized boolean init() {
        try {
            HikariConfig hc = new HikariConfig();
            hc.setJdbcUrl(cfg.getUrl());
            hc.setUsername(cfg.getUsername());
            hc.setPassword(cfg.getPassword());
            hc.setMaximumPoolSize(4);
            hc.setMinimumIdle(0);
            hc.setPoolName("bovin-rag1");
            hc.setConnectionTimeout(cfg.getConnectionTimeoutMs());
            pool = new HikariDataSource(hc);

            try (Connection c = pool.getConnection()) {
                exec(c, "CREATE EXTENSION IF NOT EXISTS vector");
                exec(c, "CREATE TABLE IF NOT EXISTS " + cfg.getTable() + " ("
                        + "id TEXT PRIMARY KEY, intent TEXT NOT NULL, text TEXT NOT NULL, "
                        + "embedding vector(" + dim + "))");
                exec(c, "CREATE INDEX IF NOT EXISTS idx_" + cfg.getTable() + "_emb ON " + cfg.getTable()
                        + " USING hnsw (embedding vector_cosine_ops)");
            }
            healthy = true;
            log.info("rag1 pgvector 就绪: table={}, dim={}", cfg.getTable(), dim);
        } catch (Exception e) {
            healthy = false;
            log.warn("rag1 pgvector 初始化失败,将由进程内向量库兜底: {}", e.getMessage());
            closeQuietly();
        }
        return healthy;
    }

    @Override
    public int seedIfEmpty(List<IntentExample> examples) {
        if (!healthy()) {
            return 0;
        }
        try (Connection c = pool.getConnection()) {
            long n = count(c);
            if (n > 0) {
                log.info("rag1 问例库已有 {} 条,跳过灌入", n);
                return 0;
            }
            String upsert = "INSERT INTO " + cfg.getTable() + " (id, intent, text, embedding) VALUES (?, ?, ?, ?::vector) "
                    + "ON CONFLICT (id) DO UPDATE SET intent = EXCLUDED.intent, text = EXCLUDED.text, embedding = EXCLUDED.embedding";
            try (PreparedStatement ps = c.prepareStatement(upsert)) {
                for (IntentExample e : examples) {
                    ps.setString(1, e.id());
                    ps.setString(2, e.intent().name());
                    ps.setString(3, e.text());
                    ps.setString(4, literal(embedder.embed(e.text())));
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            log.info("rag1 已灌入 {} 条标注问例", examples.size());
            return examples.size();
        } catch (Exception e) {
            log.warn("rag1 问例灌入失败(不影响检索已有数据): {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<Match> search(float[] queryVector, int topK) {
        if (!healthy() || queryVector == null || queryVector.length == 0) {
            return List.of();
        }
        // <=> 为 pgvector 余弦距离(0=同向,2=反向),相似度 = 1 - 距离;ORDER BY 距离即 HNSW 近邻
        String sql = "SELECT intent, text, 1 - (embedding <=> ?::vector) AS score FROM " + cfg.getTable()
                + " ORDER BY embedding <=> ?::vector LIMIT ?";
        try (Connection c = pool.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            String vec = literal(queryVector);
            ps.setString(1, vec);
            ps.setString(2, vec);
            ps.setInt(3, Math.max(1, topK));
            try (ResultSet rs = ps.executeQuery()) {
                List<Match> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new Match(IntentLabel.valueOf(rs.getString("intent")), rs.getString("text"), rs.getDouble("score")));
                }
                return out;
            }
        } catch (Exception e) {
            log.warn("rag1 向量检索失败,本次按无召回处理: {}", e.getMessage());
            return List.of();
        }
    }

    @Override
    public long count() {
        if (!healthy()) {
            return 0;
        }
        try (Connection c = pool.getConnection()) {
            return count(c);
        } catch (Exception e) {
            return 0;
        }
    }

    public boolean healthy() {
        return healthy && pool != null;
    }

    private long count(Connection c) throws Exception {
        try (PreparedStatement ps = c.prepareStatement("SELECT COUNT(*) FROM " + cfg.getTable());
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private void exec(Connection c, String sql) throws Exception {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.execute();
        }
    }

    /** float[] → pgvector 文本字面量 '[0.1,0.2,...]' */
    static String literal(float[] v) {
        StringBuilder sb = new StringBuilder(v.length * 9).append('[');
        for (int i = 0; i < v.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(v[i]);
        }
        return sb.append(']').toString();
    }

    private void closeQuietly() {
        if (pool != null) {
            try {
                pool.close();
            } catch (Exception ignore) {
                // 关闭失败无补救动作,静默
            }
            pool = null;
        }
    }
}
