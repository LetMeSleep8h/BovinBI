package com.eighthours.bovinbi.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.service.QueryExecutor;
import com.eighthours.bovinbi.service.SchemaRetriever;
import com.eighthours.bovinbi.service.SqlGuard;
import com.eighthours.bovinbi.support.MySqlTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实电商数据集(Kaggle Olist 巴西电商,2016-2018)装载与链路回归:
 * 断言全部锚定公开已知事实 —— 96,478 笔 delivered 订单、销售额第一大州 SP(圣保罗)、
 * 第一大类目 health_beauty、2017-11 黑五为销售峰值月;数据装错/过滤错/翻译错都会红。
 * 全程离线:Schema 召回 → SQL 守护 → 只读执行器的确定性链路,不依赖 LLM。
 */
@SpringBootTest
class RealEcommerceDatasetTest extends MySqlTestBase {

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

    private Dataset ecomDataset() {
        Dataset ds = datasetMapper.selectOne(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getName, DataLoader.ECOM_DATASET_NAME).last("LIMIT 1"));
        assertNotNull(ds, "启动装载应创建真实数据集「电商零售·巴西Olist」");
        return ds;
    }

    @Test
    void realDataLoadedWithKnownShape() {
        // Olist 公开事实:delivered 订单 96,478 笔(事实表按订单明细行展开为 110,197 行)
        assertEquals(96478L, dwhJdbcTemplate.queryForObject(
                "SELECT COUNT(DISTINCT order_id) FROM ecom_fact_order_item", Long.class));
        assertEquals(110197L, dwhJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ecom_fact_order_item", Long.class));
        // 时间跨度与体量
        assertEquals("2016-09-15", dwhJdbcTemplate.queryForObject(
                "SELECT MIN(order_date) FROM ecom_fact_order_item", String.class));
        assertEquals("2018-08-29", dwhJdbcTemplate.queryForObject(
                "SELECT MAX(order_date) FROM ecom_fact_order_item", String.class));
        assertTrue(dwhJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ecom_dim_customer", Integer.class) >= 90000, "客户维应近 10 万");
        assertTrue(dwhJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ecom_dim_product", Integer.class) >= 30000, "商品维应 3 万+");
        // 类目已英译(葡语原文不出现在维表)
        assertEquals(0, dwhJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM ecom_dim_product WHERE category IN ('beleza_saude','relogios_presentes')",
                Integer.class));
    }

    @Test
    void realWorldFactsHold() {
        // 第一大州 = SP(圣保罗),且显著领先第二名
        var topStates = dwhJdbcTemplate.queryForList(
                "SELECT c.state AS st, ROUND(SUM(f.price), 2) AS rev FROM ecom_fact_order_item f "
                        + "JOIN ecom_dim_customer c ON f.customer_id = c.customer_id "
                        + "GROUP BY 1 ORDER BY 2 DESC LIMIT 2");
        assertEquals("SP", topStates.get(0).get("st"), "销售额第一大州应为圣保罗(SP): " + topStates);
        assertTrue(((Number) topStates.get(0).get("rev")).doubleValue() > 5_000_000, "SP 销售额应 >500 万 BRL");

        // 第一大类目 = health_beauty(健康美容)
        var topCats = dwhJdbcTemplate.queryForList(
                "SELECT p.category AS cat, ROUND(SUM(f.price), 2) AS rev FROM ecom_fact_order_item f "
                        + "JOIN ecom_dim_product p ON f.product_id = p.product_id "
                        + "GROUP BY 1 ORDER BY 2 DESC LIMIT 1");
        assertEquals("health_beauty", topCats.get(0).get("cat"), "销售额第一大类目: " + topCats);

        // 峰值月 = 2017-11(巴西黑五)
        var topMonth = dwhJdbcTemplate.queryForList(
                "SELECT DATE_FORMAT(order_date, '%Y-%m') AS ym, ROUND(SUM(price), 2) AS rev "
                        + "FROM ecom_fact_order_item GROUP BY 1 ORDER BY 2 DESC LIMIT 1").get(0);
        assertEquals("2017-11", String.valueOf(topMonth.get("ym")), "销售峰值月应为 2017-11(黑五): " + topMonth);
    }

    @Test
    void schemaRecallTargetsEcommerceFields() {
        Dataset ds = ecomDataset();
        SchemaRetriever.LinkedSchema linked = schemaRetriever.retrieve(ds.getId(), "各国家销售额占比趋势");
        assertTrue(linked.whitelist().contains("ecom_fact_order_item"));
        assertTrue(linked.whitelist().contains("ecom_dim_product"));
        assertTrue(linked.whitelist().contains("ecom_dim_customer"));
        assertTrue(linked.schemaText().contains("销售额"));
        assertFalse(linked.schemaText().contains("仅 JOIN 用"), "隐藏字段不应进 prompt");
    }

    @Test
    void guardedSqlExecutesAgainstRealData() {
        Dataset ds = ecomDataset();
        String sql = "SELECT p.category AS 类目, ROUND(SUM(f.price), 2) AS 销售额, COUNT(DISTINCT f.order_id) AS 订单数 "
                + "FROM ecom_fact_order_item f JOIN ecom_dim_product p ON f.product_id = p.product_id "
                + "GROUP BY 1 ORDER BY 2 DESC LIMIT 5";
        var r = queryExecutor.execute(sqlGuard.validate(sql, linkedWhitelist(ds)));
        assertEquals(5, r.rowCount());
        assertEquals("health_beauty", r.rows().get(0).get("类目"), "守护 SQL 执行后 Top1 类目应为 health_beauty");
    }

    @Test
    void guardBlocksCrossDatasetTables() {
        Dataset ds = ecomDataset();
        assertThrows(com.eighthours.bovinbi.common.BizException.class, () ->
                sqlGuard.validate("SELECT COUNT(*) AS n FROM dwh_fact_milk", linkedWhitelist(ds)));
    }

    private java.util.Set<String> linkedWhitelist(Dataset ds) {
        return schemaRetriever.retrieve(ds.getId(), "销售额").whitelist();
    }
}
