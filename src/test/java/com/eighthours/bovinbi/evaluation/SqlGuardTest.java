package com.eighthours.bovinbi.evaluation;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.service.SqlGuard;
import com.eighthours.bovinbi.config.BovinProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** SQL 守护安全测试:注入/越权/多语句/强制LIMIT */
class SqlGuardTest {

    private SqlGuard guard;

    @BeforeEach
    void setUp() {
        guard = new SqlGuard(new BovinProperties());
    }

    private static final Set<String> WL = Set.of("dwh_fact_milk", "dwh_dim_cattle", "dwh_dim_farm");

    @Test
    void passSimpleSelect() {
        String sql = guard.validate("SELECT SUM(m.milk_yield) FROM dwh_fact_milk m", WL);
        assertTrue(sql.toLowerCase().contains("limit 1000"), "应强制追加 LIMIT");
    }

    @Test
    void rejectUpdate() {
        assertThrows(BizException.class,
                () -> guard.validate("UPDATE dwh_fact_milk SET milk_yield = 0", WL));
    }

    @Test
    void rejectDrop() {
        assertThrows(BizException.class, () -> guard.validate("DROP TABLE dwh_fact_milk", WL));
    }

    @Test
    void rejectMultiStatement() {
        assertThrows(BizException.class,
                () -> guard.validate("SELECT 1 FROM dwh_fact_milk; DROP TABLE dwh_fact_milk", WL));
    }

    @Test
    void rejectUnknownTable() {
        assertThrows(BizException.class,
                () -> guard.validate("SELECT * FROM mysql.user", WL));
        assertThrows(BizException.class,
                () -> guard.validate("SELECT * FROM sys_user", WL));
    }

    @Test
    void stripCommentsBeforeValidation() {
        // 行内注释应被剥离后校验,注释里藏的关键词不应影响合法 SELECT
        String sql = guard.validate("SELECT 1 FROM dwh_fact_milk -- 注释 DROP TABLE", WL);
        assertFalse(sql.contains("--"), "注释应被剥离");
        assertTrue(sql.toLowerCase().contains("select 1"));
    }

    @Test
    void keepExistingLimit() {
        String sql = guard.validate("SELECT c.breed FROM dwh_fact_milk m JOIN dwh_dim_cattle c ON m.cattle_id = c.id GROUP BY 1 LIMIT 5", WL);
        assertTrue(sql.toLowerCase().contains("limit 5"));
    }

    @Test
    void allowWhitelistedJoin() {
        String sql = guard.validate("""
                SELECT c.breed, SUM(m.milk_yield) FROM dwh_fact_milk m
                LEFT JOIN dwh_dim_cattle c ON m.cattle_id = c.id
                GROUP BY 1""", WL);
        assertTrue(sql.toUpperCase().contains("LIMIT"));
    }
}
