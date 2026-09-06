package com.eighthours.bovinbi.dto;

import java.time.LocalDateTime;

public record SessionVO(Long id, Long datasetId, String datasetName, String title,
                        LocalDateTime createdAt, LocalDateTime updatedAt) {
}
