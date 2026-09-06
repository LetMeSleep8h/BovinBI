package com.eighthours.bovinbi.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record ChatReq(@NotNull Long sessionId, @NotBlank String question) {
}
