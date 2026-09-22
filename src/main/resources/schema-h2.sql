-- BovinBI 平台元数据表(H2,幂等建表;MySQL 版含注释见 sql/mysql-schema.sql)

CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE,
    password    VARCHAR(128) NOT NULL,
    nickname    VARCHAR(64),
    role        VARCHAR(32)  DEFAULT 'ANALYST',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dataset (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(128) NOT NULL,
    description  VARCHAR(512),
    dwh_tables   VARCHAR(256),
    status       VARCHAR(16)  DEFAULT 'ACTIVE',
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS dataset_field (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id  BIGINT       NOT NULL,
    table_name  VARCHAR(64)  NOT NULL,
    column_name VARCHAR(64)  NOT NULL,
    alias       VARCHAR(64),
    field_type  VARCHAR(16),
    data_type   VARCHAR(32),
    agg_type    VARCHAR(16),
    synonyms    VARCHAR(512),
    description VARCHAR(512),
    is_hidden   TINYINT      DEFAULT 0,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_session (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    dataset_id BIGINT      NOT NULL,
    title      VARCHAR(128),
    created_at DATETIME    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT      NOT NULL,
    role       VARCHAR(16),
    content    TEXT,
    payload    TEXT,
    created_at DATETIME    DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS query_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT,
    dataset_id  BIGINT,
    question    VARCHAR(512),
    final_sql   TEXT,
    engine      VARCHAR(16),
    status      VARCHAR(16),
    row_count   INT,
    cost_ms     INT,
    cache_hit   TINYINT     DEFAULT 0,
    error_msg   VARCHAR(1024),
    created_at  DATETIME    DEFAULT CURRENT_TIMESTAMP
);

-- LLM 网关调用量化(每请求一行:成功/失败都落,供成本核算与供应商质量分析)
CREATE TABLE IF NOT EXISTS gateway_usage (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    ts                DATETIME     DEFAULT CURRENT_TIMESTAMP,
    provider          VARCHAR(64),
    model             VARCHAR(64),
    caller            VARCHAR(128),
    prompt_tokens     INT,
    completion_tokens INT,
    cost_ms           INT,
    success           TINYINT      DEFAULT 1,
    error_msg         VARCHAR(512),
    created_at        DATETIME     DEFAULT CURRENT_TIMESTAMP
);

-- 引擎标签扩容:多 Agent 降级链的 engine 标记(如 MULTI_AGENT(降级RULE))超过 16 字符
ALTER TABLE query_log ALTER COLUMN engine VARCHAR(32);
