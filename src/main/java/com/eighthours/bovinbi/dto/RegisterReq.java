package com.eighthours.bovinbi.dto;

import jakarta.validation.constraints.NotBlank;

/** 自助注册请求(nickname 可空,默认用户名) */
public record RegisterReq(@NotBlank String username,
                          @NotBlank String password,
                          String nickname) {
}
