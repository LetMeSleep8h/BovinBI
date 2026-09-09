package com.eighthours.bovinbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.entity.DatasetField;
import com.eighthours.bovinbi.mapper.DatasetFieldMapper;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Schema Linking(模式链接),管线第 3 步:根据问题关键词给字段打分排序,
 * 生成紧凑的 Schema 描述,同时带出可查询物理表白名单(一次查询喂给后续三步)。
 * 面试要点:不把全库 Schema 塞给 LLM,而是"先召回、后生成",Token 消耗大幅下降,且能避免幻觉字段。
 */
@Service
@RequiredArgsConstructor
public class SchemaLinker {

    private final DatasetMapper datasetMapper;
    private final DatasetFieldMapper fieldMapper;

    /** 召回产物:紧凑 Schema 文本(喂 LLM)+ 表白名单(喂 SQL 守护) */
    public record LinkedSchema(String schemaText, Set<String> whitelist) {
    }

    public LinkedSchema link(Long datasetId, String question) {
        Dataset ds = datasetMapper.selectById(datasetId);
        if (ds == null || ds.getDwhTables() == null || ds.getDwhTables().isBlank()) {
            throw new BizException("数据集不存在或未配置表白名单");
        }
        Set<String> whitelist = new HashSet<>(Arrays.asList(ds.getDwhTables().toLowerCase().split("[,，\\s]+")));

        List<DatasetField> all = fieldMapper.selectList(new LambdaQueryWrapper<DatasetField>()
                .eq(DatasetField::getDatasetId, datasetId)
                .eq(DatasetField::getIsHidden, 0)
                .orderByAsc(DatasetField::getId));

        String q = question == null ? "" : question;
        record Scored(DatasetField f, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (DatasetField f : all) {
            int s = 0;
            if (f.getAlias() != null && !f.getAlias().isBlank() && q.contains(f.getAlias())) s += 3;
            if (q.contains(f.getColumnName())) s += 2;
            if (f.getSynonyms() != null) {
                for (String syn : f.getSynonyms().split("[,，]")) {
                    if (!syn.isBlank() && q.contains(syn.trim())) s += 2;
                }
            }
            scored.add(new Scored(f, s));
        }
        // 命中问题的字段排在 Schema 前部,LLM 的注意力资源留给最相关的列
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        List<DatasetField> ordered = scored.stream().map(Scored::f).toList();

        StringBuilder sb = new StringBuilder();
        sb.append("【数据集】").append(ds.getName()).append(" —— ").append(ds.getDescription()).append('\n');
        sb.append("【可查询物理表】").append(ds.getDwhTables()).append('\n');
        String currentTable = null;
        for (DatasetField f : ordered) {
            if (!f.getTableName().equals(currentTable)) {
                currentTable = f.getTableName();
                sb.append("【表 ").append(currentTable).append("】\n");
            }
            sb.append("  ").append(f.getTableName()).append('.').append(f.getColumnName())
                    .append(' ').append(f.getDataType())
                    .append(" 业务名:").append(f.getAlias());
            if ("METRIC".equals(f.getFieldType())) {
                sb.append("(指标,聚合方式:").append(f.getAggType()).append(')');
            } else {
                sb.append("(维度)");
            }
            if (f.getSynonyms() != null && !f.getSynonyms().isBlank()) {
                sb.append(" 同义词:[").append(f.getSynonyms()).append(']');
            }
            if (f.getDescription() != null && !f.getDescription().isBlank()) {
                sb.append(" 口径:").append(f.getDescription());
            }
            sb.append('\n');
        }
        return new LinkedSchema(sb.toString(), whitelist);
    }
}
