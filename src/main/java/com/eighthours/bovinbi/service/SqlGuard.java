package com.eighthours.bovinbi.service;

import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.config.BovinProperties;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.Limit;
import net.sf.jsqlparser.util.TablesNamesFinder;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;

/**
 * SQL 守护(安全执行前的最后一道防线):
 * 1) 只允许单条 SELECT;2) AST 级表白名单校验;3) 强制 LIMIT 防止全表拖库;4) 去注释/去分号。
 * 配合只读连接 + queryTimeout,构成纵深防御。
 */
@Component
public class SqlGuard {

    private final BovinProperties props;

    public SqlGuard(BovinProperties props) {
        this.props = props;
    }

    public String validate(String rawSql, Set<String> whitelist) {
        String sql = rawSql == null ? "" : rawSql.trim();
        // 去掉尾部分号与注释
        while (sql.endsWith(";")) {
            sql = sql.substring(0, sql.length() - 1).trim();
        }
        sql = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)^\\s*--.*$", " ").trim();
        if (sql.isEmpty()) {
            throw new BizException("生成的 SQL 为空");
        }
        if (sql.contains(";")) {
            throw new BizException("拒绝执行:包含多条语句");
        }

        Statement stmt;
        try {
            stmt = CCJSqlParserUtil.parse(sql);
        } catch (Exception e) {
            throw new BizException("SQL 语法解析失败: " + e.getMessage());
        }
        if (!(stmt instanceof Select select)) {
            throw new BizException("安全策略:仅允许 SELECT 查询");
        }

        // AST 级表白名单
        Set<String> used = new HashSet<>();
        try {
            TablesNamesFinder<Void> finder = new TablesNamesFinder<>();
            used.addAll(finder.getTables(stmt));
        } catch (Exception e) {
            throw new BizException("SQL 表名解析失败");
        }
        if (used.isEmpty()) {
            throw new BizException("拒绝执行:未识别到目标表");
        }
        for (String t : used) {
            String lt = t.replace("`", "").replace("\"", "");
            if (lt.contains(".")) {
                lt = lt.substring(lt.lastIndexOf('.') + 1);
            }
            lt = lt.toLowerCase();
            if (!whitelist.contains(lt)) {
                throw new BizException(403, "安全策略:SQL 引用了未授权的表 [" + t + "]");
            }
        }

        // 强制 LIMIT
        int maxRows = props.getChat().getMaxRows();
        if (select instanceof PlainSelect ps) {
            if (ps.getLimit() == null) {
                Limit limit = new Limit();
                limit.setRowCount(new LongValue(maxRows));
                ps.setLimit(limit);
            }
            return ps.toString();
        }
        // 集合操作(UNION 等)直接字符串追加;(?s) 让 . 匹配换行
        if (!sql.toLowerCase().matches("(?s).*\\blimit\\s+\\d+.*")) {
            sql = sql + " LIMIT " + maxRows;
        }
        return sql;
    }
}
