package com.eighthours.bovinbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.mcp.McpToolRegistry;
import com.eighthours.bovinbi.security.UserContext;
import com.eighthours.bovinbi.support.MySqlTestBase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 数据批量进出契约(MySQL 容器 + 真实数据):
 * 1) 导出:SQL 过守护后执行 → CSV(UTF-8 BOM,Excel 中文不乱码);跨白名单表被拒;
 * 2) 批量导出:多条打 ZIP,单条失败进 errors.txt 不中断整批;
 * 3) 导入:白名单表 + 已配置列 + 标识符三道闸;非 ADMIN 403;值参数绑定写入;
 * 4) MCP 统一入口:exportReport/batchImportData 与 HTTP 端点同一服务同一安全边界。
 */
@SpringBootTest
class DataPortServiceTest extends MySqlTestBase {

    @Autowired
    private DataPortService dataPortService;
    @Autowired
    private DatasetMapper datasetMapper;
    @Autowired
    private JdbcTemplate dwhJdbcTemplate;
    @Autowired
    private McpToolRegistry registry;

    private Long dairyId() {
        return byName("牧场养殖分析");
    }

    private Long realId() {
        return byName("全球牛奶产量");
    }

    private Long byName(String name) {
        Dataset ds = datasetMapper.selectOne(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getName, name).last("LIMIT 1"));
        assertNotNull(ds, "数据集应存在: " + name);
        return ds.getId();
    }

    @AfterEach
    void clean() {
        UserContext.clear();
        dwhJdbcTemplate.update("DELETE FROM dwh_dim_country WHERE code LIKE 'ZZ%'");
    }

    @Test
    void exportCsvProducesExcelFriendlyBytes() {
        byte[] csv = dataPortService.exportCsv(dairyId(),
                "SELECT c.breed AS 品种, ROUND(SUM(m.milk_yield), 2) AS 产奶量 "
                        + "FROM dwh_fact_milk m LEFT JOIN dwh_dim_cattle c ON m.cattle_id = c.id "
                        + "GROUP BY 1 ORDER BY 2 DESC LIMIT 5");
        String text = new String(csv, StandardCharsets.UTF_8);
        assertTrue(csv[0] == (byte) 0xEF && csv[1] == (byte) 0xBB && csv[2] == (byte) 0xBF, "应带 UTF-8 BOM");
        assertTrue(text.contains("品种") && text.contains("产奶量"), "表头应为中文别名: " + text);
        assertTrue(text.lines().count() >= 2, "应有数据行");
    }

    @Test
    void exportRejectsCrossDatasetTables() {
        assertThrows(com.eighthours.bovinbi.common.BizException.class, () ->
                dataPortService.exportCsv(realId(), "SELECT COUNT(*) AS n FROM dwh_fact_milk"));
    }

    @Test
    void batchExportZipIsolatesFailures() throws Exception {
        byte[] zip = dataPortService.exportZip(realId(), List.of(
                new DataPortService.NamedSql("top国家", "SELECT c.name AS 国家, f.milk_tonnes AS 吨 "
                        + "FROM dwh_fact_milk_prod f JOIN dwh_dim_country c ON f.country_code = c.code "
                        + "ORDER BY f.milk_tonnes DESC LIMIT 3"),
                new DataPortService.NamedSql("坏查询", "SELECT * FROM secret_table LIMIT 1")));
        java.util.List<String> entries = new java.util.ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new java.io.ByteArrayInputStream(zip))) {
            ZipEntry e;
            while ((e = zin.getNextEntry()) != null) {
                entries.add(e.getName());
            }
        }
        assertTrue(entries.stream().anyMatch(n -> n.startsWith("01-top") && n.endsWith(".csv")), "应有成功条目: " + entries);
        assertTrue(entries.contains("errors.txt"), "失败条目应进 errors.txt: " + entries);
    }

    @Test
    void importCsvWritesRowsAndEnforcesGates() {
        UserContext.set(999L, "admin-u", "ADMIN");
        int rows = dataPortService.importCsv(realId(), "dwh_dim_country",
                new StringReader("code,name\nZZT,测试国\nZZU,演示国\n"));
        assertEquals(2, rows);
        assertEquals(2, dwhJdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dwh_dim_country WHERE code LIKE 'ZZ%'", Integer.class));

        // 非管理员拒绝
        UserContext.set(998L, "plain-u", "ANALYST");
        assertThrows(com.eighthours.bovinbi.common.BizException.class, () ->
                dataPortService.importCsv(realId(), "dwh_dim_country",
                        new StringReader("code,name\nZZV,又一国\n")));

        // 列不在配置内拒绝(code 是隐藏配置字段,允许;secret_col 未配置)
        UserContext.set(999L, "admin-u", "ADMIN");
        com.eighthours.bovinbi.common.BizException bad = assertThrows(
                com.eighthours.bovinbi.common.BizException.class, () ->
                        dataPortService.importCsv(realId(), "dwh_dim_country",
                                new StringReader("secret_col\n1\n")));
        assertTrue(bad.getMessage().contains("已配置业务字段"));

        // 白名单外表拒绝
        assertThrows(com.eighthours.bovinbi.common.BizException.class, () ->
                dataPortService.importCsv(realId(), "dwh_fact_milk", new StringReader("id\n1\n")));
    }

    @Test
    void mcpRegistryExposesPortToolsWithSameGuards() {
        Map<String, Object> args = Map.of("datasetId", dairyId(),
                "sql", "SELECT c.breed AS 品种 FROM dwh_fact_milk m "
                        + "LEFT JOIN dwh_dim_cattle c ON m.cattle_id = c.id LIMIT 3");
        var result = registry.call("exportReport", args);
        assertTrue(!result.isError());
        assertTrue(result.text().contains("品种"), "MCP 导出应返回 CSV 文本: " + result.text());

        var bad = registry.call("exportReport", Map.of("datasetId", dairyId(), "sql", "SELECT * FROM no_way"));
        assertTrue(bad.isError(), "白名单外的导出必须失败");

        var noArg = registry.call("batchImportData", Map.of("table", "dwh_dim_country", "csv", "code\nZZW\n"));
        assertTrue(noArg.isError() && noArg.text().contains("datasetId"), "循环外调用必须显式 datasetId");

        assertTrue(registry.listTools().stream()
                        .anyMatch(d -> "exportReport".equals(d.name()) || "batchImportData".equals(d.name())),
                "统一目录应包含新工具");
    }
}
