package com.eighthours.bovinbi.controller;

import com.eighthours.bovinbi.config.BovinProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class HealthController {

    private final BovinProperties props;

    @Value("${spring.application.name:BovinBI}")
    private String appName;

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "status", "UP",
                "app", appName,
                "llmProvider", props.getLlm().getProvider(),
                "time", LocalDateTime.now().toString());
    }
}
