package com.eighthours.bovinbi.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.service.QueryExecutor;
import com.eighthours.bovinbi.service.SchemaRetriever;
import com.eighthours.bovinbi.service.SqlGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实数据集(OWID/FAOSTAT 全球牛奶产量)装载与链路回归:
 * 合成数据验证链路通不通,真实数据验证泛化 —— 用真实世界事实做断言,
 * 数据错了(装载错列/聚合行混入/年份缺失)测试就会红。
 * 全程离线:不依赖 LLM,走 Schema 召回 → SQL 守护 → 只读执行器的确定性链路。
 */
@SpringBootTest
class RealMilkDatasetTest {

    @Autowired
    private DatasetMapper datasetMapper;
    @Autowired
    private JdbcTemplate dwhJdbcTemplate;
    @Autowired
    private SchemaRetriever schemaRetriever;
    @Autowired
    private SqlGuard sqlGuard;
    @Autowired
    private QueryExecutor queryExecutor;
    @Autowired
    private BovinProperties props;

    private Dataset realDataset() {
        Dataset ds = datasetMapper.selectOne(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getName, DataLoader.REAL_DATASET_NAME).last("LIMIT 1"));
        assertNotNull(ds, "启动装载应创建真实数据集「全球牛奶产量」");
        return ds;
    }

    @Test
    void realDataLoadedWithExpectedShape() {
        assertEquals(11075L, dwhJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwh_fact_milk_prod", Long.class), "国家行数应与源文件一致");
        assertTrue(dwhJdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT code) FROM dwh_dim_country", Integer.class) >= 180,
                "应覆盖 180+ 国家(FAO 序列实际 ~191 个三位码国家)");
        assertEquals(1961, dwhJdbcTemplate.queryForObject(
                "SELECT MIN(stat_year) FROM dwh_fact_milk_prod", Integer.class), "FAO 序列从 1961 年起");
        // 聚合行过滤:World/大洲(空码或 OWID_* 码)不得混入,否则 TopN 失真
        assertEquals(0, dwhJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwh_dim_country WHERE code NOT REGEXP '^[A-Z]{3}$'", Integer.class));
        assertEquals(0, dwhJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwh_dim_country WHERE name = 'World'", Integer.class));
    }

    @Test
    void realWorldFactHoldsIndiaIsTopProducer() {
        // 最近统计年,全球第一大牛奶生产国是印度(FAO 真实世界事实),产量亿吨级
        Integer latest = dwhJdbcTemplate.queryForObject(
                "SELECT MAX(stat_year) FROM dwh_fact_milk_prod", Integer.class);
        assertTrue(latest >= 2022, "数据应更新到 2022 年之后,实际最新年份 " + latest);
        List<Map<String, Object>> top = dwhJdbcTemplate.queryForList(
                "SELECT c.name AS country, f.milk_tonnes AS tonnes FROM dwh_fact_milk_prod f "
                        + "JOIN dwh_dim_country c ON f.country_code = c.code "
                        + "WHERE f.stat_year = " + latest + " ORDER BY f.milk_tonnes DESC LIMIT 3");
        assertEquals("India", top.get(0).get("country"), "最新年份 Top1 应为印度: " + top);
        assertTrue(((Number) top.get(0).get("tonnes")).doubleValue() > 1.0e8,
                "印度年产量应为亿吨级(>1e8 吨): " + top.get(0));
    }

    @Test
    void schemaRecallTargetsRealDatasetFields() {
        Dataset ds = realDataset();
        SchemaRetriever.LinkedSchema linked = schemaRetriever.retrieve(ds.getId(), "各国牛奶产量趋势");
        assertTrue(linked.whitelist().contains("dwh_fact_milk_prod"));
        assertTrue(linked.whitelist().contains("dwh_dim_country"));
        assertTrue(linked.schemaText().contains("牛奶产量"));
        assertTrue(linked.schemaText().contains("年份"));
        assertFalse(linked.schemaText().contains("国家代码"), "隐藏字段(code)不应进 prompt");
    }

    @Test
    void guardedSqlExecutesAgainstRealData() {
        Dataset ds = realDataset();
        String sql = "SELECT c.name AS 国家, SUM(f.milk_tonnes) AS 牛奶产量 "
                + "FROM dwh_fact_milk_prod f JOIN dwh_dim_country c ON f.country_code = c.code "
                + "GROUP BY 1 ORDER BY 2 DESC LIMIT 5";
        String guarded = sqlGuard.validate(sql, linkedWhitelist(ds));
        var r = queryExecutor.execute(guarded);
        assertEquals(5, r.rowCount());
        assertEquals("India", r.rows().get(0).get("国家"), "历史累计 Top1 应为印度");
    }

    @Test
    void guardBlocksCrossDatasetTables() {
        Dataset ds = realDataset();
        // 白名单来自数据集本身:查询另一数据集的表必须被拒绝(数据集级隔离的守护面)
        String sql = "SELECT COUNT(*) AS n FROM dwh_fact_milk";
        org.junit.jupiter.api.Assertions.assertThrows(com.eighthours.bovinbi.common.BizException.class,
                () -> sqlGuard.validate(sql, linkedWhitelist(ds)));
    }

    private java.util.Set<String> linkedWhitelist(Dataset ds) {
        return schemaRetriever.retrieve(ds.getId(), "牛奶产量").whitelist();
    }
}
