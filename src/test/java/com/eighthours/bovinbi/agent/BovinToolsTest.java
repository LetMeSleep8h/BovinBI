package com.eighthours.bovinbi.agent;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.init.DataLoader;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 工具集集成测试(H2 + 真实 SchemaLinker/SqlGuard/QueryExecutor,不依赖 LLM):
 * 固化三条工具层契约 ——
 * 1) 流程守护:未取 Schema 前 executeSql/getColumnValues 被引导先调用 getSchema;
 * 2) 错误即数据:白名单外 SQL 由守护拒绝并以文本返回(而非异常冒泡),驱动模型自修复;
 * 3) 预算强制:SQL 执行超限后返回拒绝话术,结果成功登记可按 SQL 原文命中。
 */
@SpringBootTest
class BovinToolsTest {

    @Autowired
    private BovinTools tools;

    @Autowired
    private DatasetMapper datasetMapper;

    @Autowired
    private BovinProperties props;

    private AgentRunContext ctx;

    @BeforeEach
    void setUp() {
        Dataset ds = datasetMapper.selectOne(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getName, DataLoader.DATASET_NAME).last("LIMIT 1"));
        if (ds == null) {
            ds = datasetMapper.selectList(null).get(0);
        }
        ctx = new AgentRunContext(ds.getId(), "上个月各品种产奶量", props.getChat().getAgent());
        AgentContextHolder.set(ctx);
    }

    @AfterEach
    void tearDown() {
        AgentContextHolder.clear();
    }

    @Test
    void executeSqlBeforeSchemaGuidesToGetSchemaFirst() {
        String out = tools.executeSql("SELECT 1 FROM dwh_fact_milk");
        assertTrue(out.contains("getSchema"), "未取 Schema 应引导先调用 getSchema,实际: " + out);
    }

    @Test
    void getSchemaRegistersWhitelistAndReturnsBusinessFields() {
        String out = tools.getSchema("各品种产奶量");
        assertTrue(out.contains("【数据集】"));
        assertTrue(out.toLowerCase().contains("dwh_fact_milk"));
        assertTrue(ctx.whitelist().contains("dwh_fact_milk"));
    }

    @Test
    void guardRejectionReturnsErrorAsDataNotException() {
        tools.getSchema("产奶量");
        String out = tools.executeSql("SELECT * FROM secret_table");
        assertTrue(out.startsWith("守护拒绝"), "白名单外的表应由守护拦截并返回文本,实际: " + out);
        assertTrue(out.contains("安全策略"));
    }

    @Test
    void successfulExecutionMarksResultAndReturnsPreview() {
        tools.getSchema("品种产奶量");
        String sql = """
                SELECT c.breed AS 品种, ROUND(SUM(m.milk_yield), 2) AS 产奶量
                FROM dwh_fact_milk m
                LEFT JOIN dwh_dim_cattle c ON m.cattle_id = c.id
                GROUP BY 1 ORDER BY 2 DESC LIMIT 5""";
        String out = tools.executeSql(sql);
        assertTrue(out.startsWith("执行成功"), "合法 SQL 应执行成功,实际: " + out);
        assertTrue(out.contains("品种"));
        assertNotNull(ctx.findResult(sql), "成功执行的 SQL(原文形式)必须能命中结果登记");
        assertEquals(1, ctx.trace().stream().filter(t -> "executeSql".equals(t.tool()) && t.ok()).count());
    }

    @Test
    void sqlBudgetExhaustionRefusesGracefully() {
        tools.getSchema("产奶量");
        String sql = "SELECT ROUND(SUM(m.milk_yield), 2) AS 产奶量 FROM dwh_fact_milk m LIMIT 1";
        for (int i = 0; i < 3; i++) {
            assertTrue(tools.executeSql(sql).startsWith("执行成功"));
        }
        String refusal = tools.executeSql(sql);
        assertTrue(refusal.contains("上限"), "第 4 次执行应被预算拒绝,实际: " + refusal);
        assertTrue(refusal.contains("最终 JSON"));
    }

    @Test
    void columnValuesQueryValidatesIdentifierAndReturnsValues() {
        tools.getSchema("牧场");
        String out = tools.getColumnValues("dwh_dim_farm", "farm_name", "");
        assertTrue(out.contains("可选值"), "合法维度应返回可选值,实际: " + out);

        String bad = tools.getColumnValues("dwh_dim_farm; DROP TABLE x", "farm_name", "");
        assertTrue(bad.startsWith("参数非法"), "非法标识符应被拒绝,实际: " + bad);
    }
}
