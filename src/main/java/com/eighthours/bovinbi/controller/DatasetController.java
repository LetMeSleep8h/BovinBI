package com.eighthours.bovinbi.controller;

import com.eighthours.bovinbi.common.ApiResponse;
import com.eighthours.bovinbi.dto.FieldUpdateReq;
import com.eighthours.bovinbi.entity.Dataset;
import com.eighthours.bovinbi.entity.DatasetField;
import com.eighthours.bovinbi.service.DatasetService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/datasets")
@RequiredArgsConstructor
public class DatasetController {

    private final DatasetService datasetService;

    @GetMapping
    public ApiResponse<List<Dataset>> list() {
        return ApiResponse.ok(datasetService.list());
    }

    @GetMapping("/{id}/fields")
    public ApiResponse<List<DatasetField>> fields(@PathVariable Long id) {
        return ApiResponse.ok(datasetService.fields(id));
    }

    @PutMapping("/fields/{fieldId}")
    public ApiResponse<Void> updateField(@PathVariable Long fieldId, @RequestBody FieldUpdateReq req) {
        datasetService.updateField(fieldId, req);
        return ApiResponse.ok();
    }
}
