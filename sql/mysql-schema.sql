-- BovinBI MySQL 8 建表脚本(平台元数据 + DWH 数据仓)
-- 由 docker-compose 初始化自动执行;手工安装则: mysql -uroot -p < mysql-schema.sql

CREATE DATABASE IF NOT EXISTS bovin_bi DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE bovin_bi;

-- ============ 平台元数据 ============
CREATE TABLE IF NOT EXISTS sys_user (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(64)  NOT NULL UNIQUE COMMENT '登录名',
    password    VARCHAR(128) NOT NULL COMMENT 'BCrypt',
    nickname    VARCHAR(64)  COMMENT '显示名',
    role        VARCHAR(32)  DEFAULT 'ANALYST' COMMENT 'ADMIN/ANALYST',
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
) COMMENT '用户表';

CREATE TABLE IF NOT EXISTS dataset (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(128) NOT NULL COMMENT '数据集名',
    description  VARCHAR(512) COMMENT '描述(含JOIN提示,进入NL2SQL提示词)',
    dwh_tables   VARCHAR(256) COMMENT '可查询物理表白名单,逗号分隔',
    status       VARCHAR(16)  DEFAULT 'ACTIVE',
    created_at   DATETIME     DEFAULT CURRENT_TIMESTAMP
) COMMENT '数据集(对应语义层主题)';

CREATE TABLE IF NOT EXISTS dataset_field (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    dataset_id  BIGINT       NOT NULL,
    table_name  VARCHAR(64)  NOT NULL,
    column_name VARCHAR(64)  NOT NULL,
    alias       VARCHAR(64)  COMMENT '业务别名,如 销售额',
    field_type  VARCHAR(16)  COMMENT 'DIMENSION维度/METRIC指标',
    data_type   VARCHAR(32),
    agg_type    VARCHAR(16)  COMMENT 'SUM/COUNT_DISTINCT/AVG/...',
    synonyms    VARCHAR(512) COMMENT '同义词,Schema Linking 词典',
    description VARCHAR(512) COMMENT '口径描述,进入NL2SQL提示词',
    is_hidden   TINYINT      DEFAULT 0,
    created_at  DATETIME     DEFAULT CURRENT_TIMESTAMP
) COMMENT '字段口径(维度/指标)';

CREATE TABLE IF NOT EXISTS chat_session (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id    BIGINT      NOT NULL,
    dataset_id BIGINT      NOT NULL,
    title      VARCHAR(128) COMMENT '取首问前20字',
    created_at DATETIME    DEFAULT CURRENT_TIMESTAMP
) COMMENT '会话';

CREATE TABLE IF NOT EXISTS chat_message (
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT NOT NULL,
    role       VARCHAR(16) COMMENT 'USER/ASSISTANT',
    content    TEXT COMMENT '回答摘要',
    payload    TEXT COMMENT '结构化结果JSON:SQL/列/行/图表/耗时',
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '消息';

CREATE TABLE IF NOT EXISTS query_log (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     BIGINT,
    dataset_id  BIGINT,
    question    VARCHAR(512),
    final_sql   TEXT COMMENT '守护后实际执行的SQL',
    engine      VARCHAR(32) COMMENT 'RULE/LLM/CACHE/MULTI_AGENT/FAILED',
    status      VARCHAR(16) COMMENT 'SUCCESS/FAILED/REFUSED',
    row_count   INT,
    cost_ms     INT,
    cache_hit   TINYINT DEFAULT 0,
    error_msg   VARCHAR(1024),
    created_at  DATETIME DEFAULT CURRENT_TIMESTAMP
) COMMENT '查询审计(运营+评测数据来源)';

-- ============ 分析数据仓(DWH,生产建议独立库/独立只读实例;智慧牧场·奶牛养殖) ============
CREATE TABLE IF NOT EXISTS dwh_dim_farm (
    id BIGINT PRIMARY KEY,
    farm_code VARCHAR(32) COMMENT '牧场编号',
    farm_name VARCHAR(128) COMMENT '牧场名称',
    region VARCHAR(32) COMMENT '地区:华北/西北/东北/华东',
    scale VARCHAR(16) COMMENT '规模:大型/中型/小型(按存栏数划分)'
) COMMENT '牧场维度';

CREATE TABLE IF NOT EXISTS dwh_dim_cattle (
    id BIGINT PRIMARY KEY,
    cattle_code VARCHAR(32) COMMENT '耳标号',
    breed VARCHAR(32) COMMENT '品种:荷斯坦/西门塔尔/娟姗',
    parity INT COMMENT '胎次 1~4',
    barn VARCHAR(32) COMMENT '牛舍 A~F',
    farm_id BIGINT
) COMMENT '牛只维度';

CREATE TABLE IF NOT EXISTS dwh_fact_milk (
    id BIGINT PRIMARY KEY,
    record_date DATE COMMENT '挤奶记录日期(每月5/15/25日采样)',
    cattle_id BIGINT,
    farm_id BIGINT,
    milk_yield DECIMAL(8,1) COMMENT '单次挤奶产奶量(千克)',
    fat_rate DECIMAL(5,2) COMMENT '乳脂率(%)',
    protein_rate DECIMAL(5,2) COMMENT '乳蛋白率(%)',
    lactation_stage VARCHAR(16) COMMENT '泌乳初期/中期/后期(干奶期无记录)'
) COMMENT '挤奶记录事实表';

CREATE INDEX idx_fact_date ON dwh_fact_milk (record_date);
CREATE INDEX idx_fact_cattle ON dwh_fact_milk (cattle_id);
CREATE INDEX idx_fact_farm ON dwh_fact_milk (farm_id);

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
) COMMENT 'LLM 网关调用量化';
