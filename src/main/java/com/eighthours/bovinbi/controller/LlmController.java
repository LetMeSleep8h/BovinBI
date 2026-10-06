package com.eighthours.bovinbi.controller;

import com.eighthours.bovinbi.common.ApiResponse;
import com.eighthours.bovinbi.config.BovinProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** 模型目录(前端模型下拉数据源):目录在 bovin.llm.models 配置,按请求选择生效 */
@RestController
@RequestMapping("/api/llm")
@RequiredArgsConstructor
public class LlmController {

    private final BovinProperties props;

    @GetMapping("/models")
    public ApiResponse<List<Map<String, String>>> models() {
        return ApiResponse.ok(props.getLlm().getModels().stream()
                .map(m -> Map.of("id", m.getId(), "label", m.getLabel()))
                .toList());
    }
}
