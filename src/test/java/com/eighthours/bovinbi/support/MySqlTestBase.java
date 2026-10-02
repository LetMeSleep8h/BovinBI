package com.eighthours.bovinbi.support;

import org.testcontainers.containers.MySQLContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * 集成测试的 MySQL 基座(Testcontainers,需要本机 Docker):
 * - 整个测试 JVM 共享一个一次性 MySQL(静态初始化启动,Ryuk 在 JVM 退出后回收),
 *   六个 @SpringBootTest 类复用同一容器,只有第一次有 ~20s 启动成本;
 * - 通过 DynamicPropertySource 把连接信息注入 Spring 上下文,主配置的 MySQL 地址被覆盖;
 * - rag1 关闭:pgvector 有专属容器测试(PgVectorStoreIntegrationTest),主链路测试无需 PG。
 */
public abstract class MySqlTestBase {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0")
            .withDatabaseName("bovin_bi")
            .withUsername("bovin")
            .withPassword("bovin123");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void overrideDatasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("bovin.rag1.enabled", () -> "false");
    }
}
