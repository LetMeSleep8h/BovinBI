package com.eighthours.bovinbi.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;

public record MessageVO(Long id, Long sessionId, String role, String content,
                        JsonNode payload, LocalDateTime createdAt) {
}
