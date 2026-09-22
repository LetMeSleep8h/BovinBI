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
 * Schema Linking 词面实现(管线第 3 步的默认策略):根据问题关键词给字段打分排序,
 * 生成紧凑 Schema 描述,同时带出可查询物理表白名单(一次查询喂给后续三步)。
 * 面试要点:不把全库 Schema 塞给 LLM,而是"先召回、后生成",Token 消耗大幅下降,且能避免幻觉字段;
 * 打分只决定顺序不做筛选 —— 规模化后的 topK 截断与向量重排在 {@code HybridSchemaRetriever}。
 */
@Service
@RequiredArgsConstructor
public class SchemaLinker implements SchemaRetriever {

    private final DatasetMapper datasetMapper;
    private final DatasetFieldMapper fieldMapper;

    /** linkOrdered 的产物:base 召回 + 供混合重排用的中间数据(带词面分的字段表/数据集/白名单) */
    public record OrderedLink(LinkedSchema base, Dataset ds, List<ScoredField> scored, Set<String> whitelist) {
    }

    /** 字段 + 词面得分(别名命中+3 / 列名命中+2 / 同义词命中+2,上限 7) */
    public record ScoredField(DatasetField f, int score) {
    }

    @Override
    public LinkedSchema retrieve(Long datasetId, String question) {
        return linkOrdered(datasetId, question).base();
    }

    /** 完整召回:除最终文本外,带出按词面分排序的字段表 —— HybridSchemaRetriever 在此基础上做向量重排/截断 */
    public OrderedLink linkOrdered(Long datasetId, String question) {
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
        List<ScoredField> scored = new ArrayList<>();
        for (DatasetField f : all) {
            int s = 0;
            if (f.getAlias() != null && !f.getAlias().isBlank() && q.contains(f.getAlias())) s += 3;
            if (q.contains(f.getColumnName())) s += 2;
            if (f.getSynonyms() != null) {
                for (String syn : f.getSynonyms().split("[,，]")) {
                    if (!syn.isBlank() && q.contains(syn.trim())) s += 2;
                }
            }
            scored.add(new ScoredField(f, s));
        }
        // 命中问题的字段排在 Schema 前部,LLM 的注意力资源留给最相关的列
        scored.sort(Comparator.comparingInt(ScoredField::score).reversed());
        List<DatasetField> ordered = scored.stream().map(ScoredField::f).toList();
        LinkedSchema base = new LinkedSchema(buildText(ds, ordered), whitelist);
        return new OrderedLink(base, ds, scored, whitelist);
    }

    /** 字段有序表 → 紧凑 Schema 文本(词面版与混合版共用的渲染规则) */
    public static String buildText(Dataset ds, List<DatasetField> ordered) {
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
        return sb.toString();
    }
}
