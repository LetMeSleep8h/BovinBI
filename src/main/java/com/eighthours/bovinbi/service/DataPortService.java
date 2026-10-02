package com.eighthours.bovinbi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eighthours.bovinbi.common.BizException;
import com.eighthours.bovinbi.dto.ExecResult;
import com.eighthours.bovinbi.entity.DatasetField;
import com.eighthours.bovinbi.mapper.DatasetFieldMapper;
import com.eighthours.bovinbi.mcp.tools.ToolSupport;
import com.eighthours.bovinbi.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * 数据批量进出(导入/导出)服务:同一套安全边界,三种入口共用 ——
 * HTTP 端点(/api/data/*)、MCP 工具(exportReport/batchImportData)、Agent 循环(@Tool)。
 * - 导出:SQL 必须过 SqlGuard(单条 SELECT/表白名单/强制 LIMIT)后才执行 ——
 *   导出能力不放宽任何查询权限,等于"把能问的问题存成文件";
 * - 导入:表必须在数据集白名单内、列必须是该表已配置的业务字段(标识符注入从源头堵)、
 *   分批 INSERT;导入是写操作,HTTP 入口仅 ADMIN(MCP 入口由 /mcp 的 api-keys 把守);
 * - 批量导出失败隔离:单条查询失败记入 errors.txt,不中断整批。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DataPortService {

    private static final Pattern IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    private final ToolSupport toolSupport;
    private final SqlGuard sqlGuard;
    private final QueryExecutor queryExecutor;
    private final DatasetFieldMapper fieldMapper;
    private final JdbcTemplate dwhJdbcTemplate;

    /** 批量导出的一个条目:报表名 + 查询 SQL */
    public record NamedSql(String name, String sql) {
    }

    /** 单条导出:守护 → 执行 → CSV 字节(带 UTF-8 BOM,Excel 双击中文不乱码) */
    public byte[] exportCsv(Long datasetId, String sql) {
        String guarded = sqlGuard.validate(sql, toolSupport.resolveWhitelist(datasetId, null));
        ExecResult r = queryExecutor.execute(guarded);
        return toCsv(r);
    }

    /** 批量导出:多个命名查询打成一个 ZIP(每条一份 CSV;失败条目进 errors.txt) */
    public byte[] exportZip(Long datasetId, List<NamedSql> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException(400, "导出清单为空");
        }
        List<String> errors = new ArrayList<>();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ZipOutputStream zip = new ZipOutputStream(bos)) {
            int seq = 0;
            for (NamedSql item : items) {
                seq++;
                String entry = String.format("%02d-%s", seq, sanitize(item.name()));
                try {
                    byte[] csv = exportCsv(datasetId, item.sql());
                    zip.putNextEntry(new ZipEntry(entry + ".csv"));
                    zip.write(csv);
                    zip.closeEntry();
                } catch (Exception e) {
                    errors.add(entry + ": " + e.getMessage());
                    log.warn("批量导出条目 [{}] 失败(不中断整批): {}", entry, e.getMessage());
                }
            }
            if (!errors.isEmpty()) {
                zip.putNextEntry(new ZipEntry("errors.txt"));
                zip.write(String.join("\n", errors).getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
            zip.finish();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new BizException(500, "打包导出失败: " + e.getMessage());
        }
    }

    /**
     * 批量导入:CSV 文本(首行为列名)→ 指定白名单表。
     * 三道闸:表在数据集白名单、列是该表已配置业务字段的子集、列名是简单标识符;
     * 值一律以字符串参数传入(由数据库按列类型转换),不拼接 SQL —— 无注入面。
     *
     * @return 实际插入行数
     */
    public int importCsv(Long datasetId, String table, Reader csv) {
        if (!UserContext.isAdmin()) {
            // MCP 外部调用(无请求上下文)时由 /mcp 鉴权把守;HTTP 调用必须 ADMIN
            if (UserContext.uid() != null) {
                throw new BizException(403, "批量导入仅管理员可用");
            }
        }
        Set<String> whitelist = toolSupport.resolveWhitelist(datasetId, null);
        if (table == null || !IDENTIFIER.matcher(table).matches() || !whitelist.contains(table.toLowerCase())) {
            throw new BizException(403, "目标表不在数据集白名单内: " + table);
        }
        Set<String> allowedColumns = fieldMapper.selectList(new LambdaQueryWrapper<DatasetField>()
                        .eq(DatasetField::getDatasetId, datasetId)
                        .eq(DatasetField::getTableName, table))
                .stream().map(f -> f.getColumnName().toLowerCase()).collect(java.util.stream.Collectors.toSet());
        if (allowedColumns.isEmpty()) {
            throw new BizException(403, "该表未在数据集配置业务字段,禁止导入: " + table);
        }
        try (org.apache.commons.csv.CSVParser parser = CSVFormat.DEFAULT.builder()
                .setHeader().setSkipHeaderRecord(true).build().parse(csv)) {
            List<String> header = new ArrayList<>(parser.getHeaderNames());
            for (String col : header) {
                if (!IDENTIFIER.matcher(col).matches() || !allowedColumns.contains(col.toLowerCase())) {
                    throw new BizException(400, "列不在已配置业务字段内: " + col + "(允许: " + allowedColumns + ")");
                }
            }
            List<Object[]> batch = new ArrayList<>();
            int total = 0;
            for (org.apache.commons.csv.CSVRecord r : parser) {
                Object[] args = new Object[header.size()];
                for (int i = 0; i < header.size(); i++) {
                    String v = r.size() > i ? r.get(i) : null;
                    args[i] = v == null || v.isBlank() || "NULL".equalsIgnoreCase(v) ? null : v;
                }
                batch.add(args);
                if (batch.size() >= 1000) {
                    total += insertBatch(table, header, batch);
                    batch.clear();
                }
            }
            if (!batch.isEmpty()) {
                total += insertBatch(table, header, batch);
            }
            log.info("批量导入完成: dataset={} table={} rows={}", datasetId, table, total);
            return total;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw new BizException(400, "CSV 解析/写入失败: " + e.getMessage());
        }
    }

    /**
     * 分批 INSERT:走 DWH 通道(默认与主库同池可写;生产分库时只读数仓应走数据管道导入,
     * 此方法面向"演示/自管数仓"场景)。
     */
    private int insertBatch(String table, List<String> header, List<Object[]> batch) {
        String cols = String.join(", ", header);
        String placeholders = String.join(", ", header.stream().map(c -> "?").toList());
        dwhJdbcTemplate.batchUpdate("INSERT INTO " + table + " (" + cols + ") VALUES (" + placeholders + ")", batch);
        return batch.size();
    }

    private byte[] toCsv(ExecResult r) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             CSVPrinter printer = new CSVPrinter(new java.io.OutputStreamWriter(bos, StandardCharsets.UTF_8), CSVFormat.DEFAULT)) {
            bos.write(0xEF); bos.write(0xBB); bos.write(0xBF); // UTF-8 BOM
            for (var c : r.columns()) {
                printer.print(c.name());
            }
            printer.println();
            for (var row : r.rows()) {
                for (Object v : row.values()) {
                    printer.print(v == null ? "" : String.valueOf(v));
                }
                printer.println();
            }
            printer.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new BizException(500, "CSV 生成失败: " + e.getMessage());
        }
    }

    /** 报表名 → 安全文件名(去路径分隔与空白) */
    private String sanitize(String name) {
        String s = name == null ? "report" : name.replaceAll("[/\\\\\\s:]+", "_");
        return (s.length() > 60 ? s.substring(0, 60) : s).isEmpty() ? "report" : s;
    }
}
