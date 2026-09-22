package com.eighthours.bovinbi.service;

import java.util.Set;

/**
 * Schema 召回接口(管线第 3 步的抽象):按问题召回紧凑 Schema 文本 + 表白名单。
 * 实现谱系:
 * - {@link SchemaLinker}:词面打分(业务名+3/列名+2/同义词+2),排序不筛选 —— 默认实现;
 * - {@link com.eighthours.bovinbi.service.rag.HybridSchemaRetriever}:词面 + 向量重排 + topK 截断
 *   (bovin.rag.enabled=true 时以 @Primary 接管),面向几十上百张表的规模。
 * 接口先行:消费方(Nl2SqlService/BovinTools/AgentOrchestrator/MultiAgentServiceImpl)只依赖本接口,
 * 召回策略升级对链路零改动。
 */
public interface SchemaRetriever {

    /** 召回产物:紧凑 Schema 文本(喂 LLM)+ 表白名单(喂 SQL 守护) */
    record LinkedSchema(String schemaText, Set<String> whitelist) {
    }

    LinkedSchema retrieve(Long datasetId, String question);
}
