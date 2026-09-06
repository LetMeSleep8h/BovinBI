package com.eighthours.bovinbi.init;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.entity.ChatMessage;
import com.eighthours.bovinbi.entity.ChatSession;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.entity.DatasetField;
import com.eighthours.bovinbi.entity.User;
import com.eighthours.bovinbi.mapper.ChatMessageMapper;
import com.eighthours.bovinbi.mapper.ChatSessionMapper;
import com.eighthours.bovinbi.mapper.DatasetFieldMapper;
import com.eighthours.bovinbi.mapper.DatasetMapper;
import com.eighthours.bovinbi.mapper.UserMapper;
import com.eighthours.bovinbi.service.AuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动初始化:种子用户/数据集元数据 → DWH 建表 → 牧场数据集 CSV 装载(幂等,空表才装载)。
 * 数据来源:dataset/build_bovine_dwh.py 生成的「智慧牧场·奶牛养殖」合成数据集,
 * 业务规律(品种/胎次/泌乳阶段/季节/牧场规模)全部可审计、可复现,详见 dataset/README.md。
 * 旧版「电商销售分析」数据集在启动时自动迁移清理(删旧表/旧元数据/关联会话)。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataLoader implements ApplicationRunner {

    public static final String DATASET_NAME = "牧场养殖分析";

    private final UserMapper userMapper;
    private final DatasetMapper datasetMapper;
    private final DatasetFieldMapper fieldMapper;
    private final ChatSessionMapper sessionMapper;
    private final ChatMessageMapper messageMapper;
    private final AuthService authService;
    private final JdbcTemplate dwhJdbcTemplate;

    @org.springframework.beans.factory.annotation.Value("${spring.datasource.url:}")
    private String platformUrl;

    @Override
    public void run(ApplicationArguments args) {
        seedMetadata();
        ensureH2Compatibility();
        dropLegacyDwhTables();
        ensureDwhTables();
        loadDwhData();
    }

    /**
     * H2(MySQL 兼容模式)缺少 DATE_FORMAT 等方言函数:注册别名后,
     * 生成的 MySQL SQL 在 H2 演示库与 MySQL 生产库零改动互通。
     */
    private void ensureH2Compatibility() {
        if (!platformUrl.contains(":h2:")) {
            return;
        }
        try {
            dwhJdbcTemplate.execute("CREATE ALIAS IF NOT EXISTS DATE_FORMAT FOR 'com.eighthours.bovinbi.util.H2Functions.dateFormat'");
            log.info("已为 H2 注册 MySQL 兼容函数别名: DATE_FORMAT");
        } catch (Exception e) {
            log.warn("注册 H2 函数别名失败(不影响启动): {}", e.getMessage());
        }
    }

    // ---------------- 元数据种子 ----------------

    private void seedMetadata() {
        if (userMapper.selectCount(null) == 0) {
            User admin = new User();
            admin.setUsername("admin");
            admin.setPassword(authService.encode("bovin123"));
            admin.setNickname("牧场数据分析师");
            admin.setRole("ADMIN");
            userMapper.insert(admin);
            log.info("初始化种子用户 admin/bovin123");
        }
        Dataset exists = datasetMapper.selectOne(new LambdaQueryWrapper<Dataset>()
                .eq(Dataset::getName, DATASET_NAME).last("LIMIT 1"));
        if (exists != null) {
            return;
        }
        // 换数据集:清理旧版数据集元数据与关联会话(如 v1 的「电商销售分析」)
        long legacyFields = fieldMapper.selectCount(null);
        if (legacyFields > 0) {
            fieldMapper.delete(null);
            messageMapper.delete(null);
            sessionMapper.delete(null);
            datasetMapper.delete(null);
            log.info("检测到旧版数据集元数据({} 字段),已迁移清理", legacyFields);
        }
        Dataset ds = new Dataset();
        ds.setName(DATASET_NAME);
        ds.setDescription("奶牛养殖业务数据(挤奶记录/牛只/牧场,合成数据集);JOIN关系: dwh_fact_milk.cattle_id=dwh_dim_cattle.id, dwh_fact_milk.farm_id=dwh_dim_farm.id");
        ds.setDwhTables("dwh_fact_milk,dwh_dim_cattle,dwh_dim_farm");
        ds.setStatus("ACTIVE");
        datasetMapper.insert(ds);

        List<DatasetField> fields = List.of(
                field(ds.getId(), "dwh_fact_milk", "record_date", "记录日期", "DIMENSION", "DATE", "NONE",
                        "日期,时间,挤奶日期,采样日期", "挤奶记录日期,粒度为天,每月5/15/25日采样"),
                field(ds.getId(), "dwh_fact_milk", "milk_yield", "产奶量", "METRIC", "DECIMAL", "SUM",
                        "产奶量,奶量,产奶,挤奶量,产量", "单次挤奶产奶量(千克)"),
                field(ds.getId(), "dwh_fact_milk", "milk_yield", "平均单产", "METRIC", "DECIMAL", "AVG",
                        "单产,平均单产,日均单产,每头牛产量", "单次挤奶产奶量均值(千克/头·次)"),
                field(ds.getId(), "dwh_fact_milk", "cattle_id", "泌乳牛数", "METRIC", "BIGINT", "COUNT_DISTINCT",
                        "泌乳牛数,牛只数,牛的数量,头数,牛数,泌乳牛,在泌牛", "当日有挤奶记录的牛只去重数"),
                field(ds.getId(), "dwh_fact_milk", "fat_rate", "乳脂率", "METRIC", "DECIMAL", "AVG",
                        "乳脂率,脂肪率,乳脂", "乳脂百分比,反映原奶质量"),
                field(ds.getId(), "dwh_fact_milk", "protein_rate", "乳蛋白率", "METRIC", "DECIMAL", "AVG",
                        "乳蛋白率,蛋白率,乳蛋白", "乳蛋白百分比,反映原奶营养"),
                field(ds.getId(), "dwh_fact_milk", "lactation_stage", "泌乳阶段", "DIMENSION", "VARCHAR", "NONE",
                        "泌乳阶段,泌乳期,阶段,初期,中期,后期", "泌乳初期/中期/后期"),
                field(ds.getId(), "dwh_dim_cattle", "breed", "品种", "DIMENSION", "VARCHAR", "NONE",
                        "品种,牛种,荷斯坦,西门塔尔,娟姗", "荷斯坦/西门塔尔/娟姗"),
                field(ds.getId(), "dwh_dim_cattle", "barn", "牛舍", "DIMENSION", "VARCHAR", "NONE",
                        "牛舍,栏舍,牛栏", "牛舍A~F"),
                field(ds.getId(), "dwh_dim_cattle", "parity", "胎次", "DIMENSION", "INT", "NONE",
                        "胎次,第几胎", "第1~4胎,2~3胎为泌乳高峰"),
                field(ds.getId(), "dwh_dim_farm", "farm_name", "牧场", "DIMENSION", "VARCHAR", "NONE",
                        "牧场,农场,基地", "牧场名称"),
                field(ds.getId(), "dwh_dim_farm", "region", "地区", "DIMENSION", "VARCHAR", "NONE",
                        "地区,区域,大区", "华北/西北/东北/华东"),
                field(ds.getId(), "dwh_dim_farm", "scale", "牧场规模", "DIMENSION", "VARCHAR", "NONE",
                        "规模,牧场规模,大型牧场,中型牧场,小型牧场", "大型/中型/小型,按存栏数划分"));
        fields.forEach(fieldMapper::insert);
        log.info("初始化示例数据集「{}」,字段数={}", DATASET_NAME, fields.size());
    }

    private DatasetField field(Long dsId, String table, String col, String alias, String type,
                               String dataType, String agg, String synonyms, String desc) {
        DatasetField f = new DatasetField();
        f.setDatasetId(dsId);
        f.setTableName(table);
        f.setColumnName(col);
        f.setAlias(alias);
        f.setFieldType(type);
        f.setDataType(dataType);
        f.setAggType(agg);
        f.setSynonyms(synonyms);
        f.setDescription(desc);
        f.setIsHidden(0);
        return f;
    }

    // ---------------- DWH 建表(H2/MySQL 通用) ----------------

    /** v1 电商星型模型已下线,清掉旧表(幂等) */
    private void dropLegacyDwhTables() {
        for (String t : new String[]{"dwh_fact_sales", "dwh_dim_product", "dwh_dim_customer"}) {
            try {
                dwhJdbcTemplate.execute("DROP TABLE IF EXISTS " + t);
            } catch (Exception e) {
                log.warn("清理旧表 {} 失败(不影响启动): {}", t, e.getMessage());
            }
        }
    }

    private void ensureDwhTables() {
        dwhJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dwh_dim_farm (
                  id BIGINT PRIMARY KEY,
                  farm_code VARCHAR(32),
                  farm_name VARCHAR(128),
                  region VARCHAR(32),
                  scale VARCHAR(16))""");
        dwhJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dwh_dim_cattle (
                  id BIGINT PRIMARY KEY,
                  cattle_code VARCHAR(32),
                  breed VARCHAR(32),
                  parity INT,
                  barn VARCHAR(32),
                  farm_id BIGINT)""");
        dwhJdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS dwh_fact_milk (
                  id BIGINT PRIMARY KEY,
                  record_date DATE,
                  cattle_id BIGINT,
                  farm_id BIGINT,
                  milk_yield DECIMAL(8,1),
                  fat_rate DECIMAL(5,2),
                  protein_rate DECIMAL(5,2),
                  lactation_stage VARCHAR(16))""");
        log.info("DWH 表已就绪(牧场/牛只/挤奶记录)");
    }

    // ---------------- CSV 装载(幂等) ----------------

    private void loadDwhData() {
        if (dwhJdbcTemplate.queryForObject("SELECT COUNT(*) FROM dwh_fact_milk", Integer.class) > 0) {
            log.info("DWH 已有数据,跳过装载");
            return;
        }
        long t0 = System.currentTimeMillis();
        try {
            int dimFarm = loadCsv("dataset/dwh/dim_farm.csv",
                    "INSERT INTO dwh_dim_farm (id, farm_code, farm_name, region, scale) VALUES (?,?,?,?,?)",
                    5);
            int dimCattle = loadCsv("dataset/dwh/dim_cattle.csv",
                    "INSERT INTO dwh_dim_cattle (id, cattle_code, breed, parity, barn, farm_id) VALUES (?,?,?,?,?,?)",
                    6);
            int fact = loadCsv("dataset/dwh/fact_milk.csv",
                    "INSERT INTO dwh_fact_milk (id, record_date, cattle_id, farm_id, milk_yield, fat_rate, protein_rate, lactation_stage) VALUES (?,?,?,?,?,?,?,?)",
                    8);
            log.info("DWH 数据装载完成: 牧场 {} 行, 牛只 {} 行, 挤奶记录 {} 行, 耗时 {}ms",
                    dimFarm, dimCattle, fact, System.currentTimeMillis() - t0);
        } catch (Exception e) {
            log.error("DWH 数据装载失败(演示功能受损,但不影响启动): {}", e.getMessage(), e);
        }
    }

    private int loadCsv(String path, String insertSql, int columns) throws Exception {
        List<Object[]> batch = new ArrayList<>();
        int total = 0;
        try (Reader reader = new InputStreamReader(new ClassPathResource(path).getInputStream(), StandardCharsets.UTF_8)) {
            Iterable<CSVRecord> records = CSVFormat.DEFAULT.builder()
                    .setHeader().setSkipHeaderRecord(true).build().parse(reader);
            for (CSVRecord r : records) {
                Object[] args = new Object[columns];
                for (int i = 0; i < columns; i++) {
                    String v = r.get(i);
                    args[i] = toTyped(path, i, v);
                }
                batch.add(args);
                if (batch.size() >= 2000) {
                    dwhJdbcTemplate.batchUpdate(insertSql, batch);
                    total += batch.size();
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                dwhJdbcTemplate.batchUpdate(insertSql, batch);
                total += batch.size();
            }
        }
        return total;
    }

    private Object toTyped(String path, int idx, String v) {
        if (v == null || v.isBlank() || v.equals("NULL")) return null;
        if (idx == 0) return Long.parseLong(v);
        if (path.contains("dim_farm")) return v;
        if (path.contains("dim_cattle")) {
            return switch (idx) {
                case 3 -> Integer.parseInt(v);
                case 5 -> Long.parseLong(v);
                default -> v;
            };
        }
        // fact_milk
        return switch (idx) {
            case 1 -> LocalDate.parse(v);
            case 2, 3 -> Long.parseLong(v);
            case 4, 5, 6 -> new java.math.BigDecimal(v);
            default -> v;
        };
    }
}
