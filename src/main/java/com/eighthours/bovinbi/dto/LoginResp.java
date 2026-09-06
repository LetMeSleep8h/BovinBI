package com.eighthours.bovinbi.dto;

public record LoginResp(String token, Long id, String username, String nickname, String role) {
}
