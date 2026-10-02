package com.eighthours.bovinbi.controller;

import com.eighthours.bovinbi.common.ApiResponse;
import com.eighthours.bovinbi.service.DataPortService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.StringReader;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 数据批量进出 HTTP 端点(与 MCP 工具同一 DataPortService,安全边界同一套):
 * - POST /api/data/export/csv   单条查询 → CSV 下载(登录即可,权限=可查询即可导出)
 * - POST /api/data/export/batch 多条命名查询 → ZIP 报表包下载
 * - POST /api/data/import/csv   CSV 批量导入白名单表(仅 ADMIN)
 */
@RestController
@RequestMapping("/api/data")
@RequiredArgsConstructor
public class DataPortController {

    private final DataPortService dataPortService;

    public record ExportReq(Long datasetId, String sql) {
    }

    public record BatchExportReq(Long datasetId, List<DataPortService.NamedSql> items) {
    }

    public record ImportReq(Long datasetId, String table, String csv) {
    }

    @PostMapping("/export/csv")
    public ResponseEntity<byte[]> exportCsv(@RequestBody ExportReq req) {
        byte[] csv = dataPortService.exportCsv(req.datasetId(), req.sql());
        return attachment(csv, fileName("report", "csv"), "text/csv;charset=UTF-8");
    }

    @PostMapping("/export/batch")
    public ResponseEntity<byte[]> exportBatch(@RequestBody BatchExportReq req) {
        byte[] zip = dataPortService.exportZip(req.datasetId(), req.items());
        return attachment(zip, fileName("reports", "zip"), "application/zip");
    }

    @PostMapping("/import/csv")
    public ApiResponse<Map<String, Object>> importCsv(@RequestBody ImportReq req) {
        int rows = dataPortService.importCsv(req.datasetId(), req.table(), new StringReader(req.csv()));
        return ApiResponse.ok(Map.of("table", req.table(), "rows", rows));
    }

    private String fileName(String prefix, String ext) {
        return prefix + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "." + ext;
    }

    private ResponseEntity<byte[]> attachment(byte[] body, String filename, String contentType) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType(contentType))
                .body(body);
    }
}
