package com.eighthours.bovinbi.service.rag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.entity.DatasetField;
import com.eighthours.bovinbi.mapper.DatasetFieldMapper;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.service.SchemaLinker;
import com.eighthours.bovinbi.service.SchemaRetriever;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 混合召回契约:topK 截断生效(字段数>K)、白名单永远全量(守护不受截断影响)、
 * 小域(字段数<=K)行为与词面版逐字节一致(演示数据集零变化的保证)。
 */
class HybridSchemaRetrieverTest {

    private DatasetMapper datasetMapper;
    private DatasetFieldMapper fieldMapper;
    private BovinProperties props;

    @BeforeEach
    void setUp() {
        datasetMapper = mock(DatasetMapper.class);
        fieldMapper = mock(DatasetFieldMapper.class);
        props = new BovinProperties();

        Dataset ds = new Dataset();
        ds.setId(1L);
        ds.setName("牧场养殖分析");
        ds.setDescription("d");
        ds.setDwhTables("dwh_fact_milk,dwh_dim_cattle,dwh_dim_farm");
        when(datasetMapper.selectById(1L)).thenReturn(ds);
        when(fieldMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(fields());
    }

    private List<DatasetField> fields() {
        return List.of(
                field("dwh_fact_milk", "milk_yield", "产奶量", "METRIC", "日产奶,奶量"),
                field("dwh_fact_milk", "fat_rate", "乳脂率", "METRIC", "脂肪率"),
                field("dwh_dim_cattle", "breed", "品种", "DIMENSION", "牛种"),
                field("dwh_dim_farm", "farm_name", "牧场", "DIMENSION", "农场"),
                field("dwh_dim_farm", "region", "地区", "DIMENSION", "区域"));
    }

    private DatasetField field(String table, String col, String alias, String type, String syn) {
        DatasetField f = new DatasetField();
        f.setTableName(table);
        f.setColumnName(col);
        f.setAlias(alias);
        f.setFieldType(type);
        f.setSynonyms(syn);
        f.setDataType("VARCHAR");
        f.setIsHidden(0);
        return f;
    }

    @Test
    void truncatesToTopKAndKeepsWhitelistFull() {
        props.getRag().setTopK(3);
        HybridSchemaRetriever retriever = new HybridSchemaRetriever(
                new SchemaLinker(datasetMapper, fieldMapper), new HashEmbeddingClient(), props);

        SchemaRetriever.LinkedSchema r = retriever.retrieve(1L, "产奶量Top10牧场按品种");

        // 截断:注入文本只含 3 个字段行(行首两空格+表名为字段行标记)
        long fieldLines = r.schemaText().lines().filter(l -> l.startsWith("  dwh_")).count();
        assertEquals(3, fieldLines);
        // 白名单永远全量 —— SQL 守护不受截断影响
        assertEquals(Set.of("dwh_fact_milk", "dwh_dim_cattle", "dwh_dim_farm"), r.whitelist());
        // 命中问题的字段保留("产奶量"/"品种"与问题词面+语义双高)
        assertTrue(r.schemaText().contains("milk_yield"));
        assertTrue(r.schemaText().contains("breed"));
    }

    @Test
    void smallDomainPassesThroughLexicalResultByteForByte() {
        props.getRag().setTopK(32);
        SchemaLinker lexical = new SchemaLinker(datasetMapper, fieldMapper);
        HybridSchemaRetriever retriever = new HybridSchemaRetriever(lexical, new HashEmbeddingClient(), props);

        SchemaRetriever.LinkedSchema expected = lexical.retrieve(1L, "产奶量Top10牧场按品种");
        SchemaRetriever.LinkedSchema actual = retriever.retrieve(1L, "产奶量Top10牧场按品种");
        assertEquals(expected.schemaText(), actual.schemaText());
        assertEquals(expected.whitelist(), actual.whitelist());
    }

    @Test
    void nullQuestionDoesNotBlowUp() {
        props.getRag().setTopK(3);
        HybridSchemaRetriever retriever = new HybridSchemaRetriever(
                new SchemaLinker(datasetMapper, fieldMapper), new HashEmbeddingClient(), props);
        SchemaRetriever.LinkedSchema r = retriever.retrieve(1L, null);
        assertEquals(3, r.schemaText().lines().filter(l -> l.startsWith("  dwh_")).count());
    }
}
