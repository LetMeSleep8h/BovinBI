package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import com.eighthours.bovinbi.dto.ColInfo;
import com.eighthours.bovinbi.dto.ExecResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * 只读查询执行器:Statement 级 queryTimeout + maxRows 双保险;对 JDBC 类型做展示友好的归一化。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QueryExecutor {

    private final JdbcTemplate dwhJdbcTemplate;
    private final BovinProperties props;

    public ExecResult execute(String sql) {
        long t0 = System.currentTimeMillis();
        try {
            ExecResult r = dwhJdbcTemplate.execute((ConnectionCallback<ExecResult>) conn -> {
                try (Statement st = conn.createStatement()) {
                    st.setQueryTimeout(props.getChat().getQueryTimeoutSeconds());
                    st.setMaxRows(props.getChat().getMaxRows());
                    try (ResultSet rs = st.executeQuery(sql)) {
                        ResultSetMetaData md = rs.getMetaData();
                        int n = md.getColumnCount();
                        List<ColInfo> cols = new ArrayList<>();
                        for (int i = 1; i <= n; i++) {
                            cols.add(new ColInfo(md.getColumnLabel(i), md.getColumnTypeName(i)));
                        }
                        List<LinkedHashMap<String, Object>> rows = new ArrayList<>();
                        while (rs.next()) {
                            LinkedHashMap<String, Object> row = new LinkedHashMap<>();
                            for (int i = 1; i <= n; i++) {
                                row.put(md.getColumnLabel(i), normalize(rs.getObject(i)));
                            }
                            rows.add(row);
                        }
                        return new ExecResult(cols, rows, rows.size(), System.currentTimeMillis() - t0);
                    }
                }
            });
            log.info("SQL 执行完成 rows={} took={}ms", r.rowCount(), r.tookMs());
            return r;
        } catch (DataAccessException e) {
            String msg = e.getMostSpecificCause() == null ? e.getMessage() : e.getMostSpecificCause().getMessage();
            log.warn("SQL 执行失败: {}, sql={}", msg, sql);
            throw new BizException("查询执行失败: " + msg);
        }
    }

    private Object normalize(Object v) {
        if (v == null) return null;
        if (v instanceof LocalDateTime ldt) {
            return ldt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
        if (v instanceof LocalDate d) {
            return d.toString();
        }
        if (v instanceof byte[] b) {
            return "[binary " + b.length + "B]";
        }
        if (v instanceof BigDecimal bd) {
            return bd.doubleValue();
        }
        return v;
    }
}
