-- rag1 意图识别向量库(PostgreSQL + pgvector)
-- 应用启动时 PgVectorStore 会幂等执行同样的 DDL,此文件供 DBA 预建/审计使用。
-- 前置:目标库执行 CREATE EXTENSION vector 需要 superuser 或扩展已装到模板库。

CREATE EXTENSION IF NOT EXISTS vector;

-- embedding 维度必须与 bovin.rag.embedding.provider 匹配:
--   hash(演示) = 256 / openai text-embedding-3-small = 1536
-- 换嵌入模型请 DROP 表重建(或换表名 bovin.rag1.table),再让应用自动重新灌语料。
CREATE TABLE IF NOT EXISTS rag1_intent_example (
    id        TEXT PRIMARY KEY,      -- hash(intent|text),幂等 upsert 键
    intent    TEXT NOT NULL,         -- 意图标签(CHIT_CHAT/TREND/TOP_N/RATIO/COMPARE/GROUP_STAT/METRIC)
    text      TEXT NOT NULL,         -- 标注问例原文
    embedding vector(256) NOT NULL
);

-- HNSW 余弦近邻索引(<=> 距离算子);语料几十条时顺序扫描也够,索引为扩容预留
CREATE INDEX IF NOT EXISTS idx_rag1_intent_example_emb
    ON rag1_intent_example USING hnsw (embedding vector_cosine_ops);

-- 人工核查/排障常用查询:
--   SELECT intent, text FROM rag1_intent_example ORDER BY intent;
--   SELECT intent, text, 1 - (embedding <=> (SELECT embedding FROM rag1_intent_example WHERE text = '近12个月每月产奶量趋势')::vector) AS score
--     FROM rag1_intent_example ORDER BY embedding <=> (SELECT embedding FROM rag1_intent_example WHERE text = '近12个月每月产奶量趋势')::vector LIMIT 5;
