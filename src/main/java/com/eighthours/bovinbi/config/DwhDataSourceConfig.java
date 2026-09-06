package com.eighthours.bovinbi.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 分析查询(DWH)执行通道:独立于平台元数据访问。
 * - 未配置 bovin.dwh.url 时复用主数据源(演示模式);
 * - 配置后创建独立只读连接池(生产建议指向只读副本/数仓),实现"平台库/分析库"分离。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class DwhDataSourceConfig {

    private final BovinProperties props;

    @Bean
    public JdbcTemplate dwhJdbcTemplate(DataSource dataSource) {
        String url = props.getDwh().getUrl();
        if (url == null || url.isBlank()) {
            log.info("DWH 数据源未单独配置,复用主数据源(演示模式)");
            return new JdbcTemplate(dataSource);
        }
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(url);
        cfg.setUsername(props.getDwh().getUsername());
        cfg.setPassword(props.getDwh().getPassword());
        cfg.setMaximumPoolSize(8);
        cfg.setMinimumIdle(1);
        cfg.setPoolName("bovin-dwh");
        cfg.setReadOnly(true);
        cfg.setConnectionTimeout(5000);
        log.info("DWH 使用独立只读数据源: {}", url);
        return new JdbcTemplate(new HikariDataSource(cfg));
    }
}
