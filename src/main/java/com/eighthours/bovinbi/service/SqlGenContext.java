package com.eighthours.bovinbi.service;

import java.util.Set;

/**
 * SQL 生成上下文:管线第 3 步(Schema 召回)的产物,贯穿生成/守护/执行三步。
 *
 * @param schemaText 紧凑 Schema 描述(喂给 LLM,防幻觉+省 Token)
 * @param whitelist  可查询物理表白名单(SQL 守护用)
 */
public record SqlGenContext(String question, TimeRange timeRange, String schemaText, Set<String> whitelist) {
}
