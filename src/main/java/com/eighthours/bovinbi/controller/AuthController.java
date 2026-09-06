package com.eighthours.bovinbi.controller;

import com.eighthours.bovinbi.common.ApiResponse;
import com.eighthours.bovinbi.dto.LoginReq;
import com.eighthours.bovinbi.dto.LoginResp;
import com.eighthours.bovinbi.entity.User;
import com.eighthours.bovinbi.security.UserContext;
import com.eighthours.bovinbi.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<LoginResp> login(@Valid @RequestBody LoginReq req) {
        return ApiResponse.ok(authService.login(req.username(), req.password()));
    }

    @GetMapping("/me")
    public ApiResponse<User> me() {
        return ApiResponse.ok(authService.me(UserContext.uid()));
    }
}
