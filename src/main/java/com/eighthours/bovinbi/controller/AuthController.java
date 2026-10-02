package com.eighthours.bovinbi.controller;

import com.eighthours.bovinbi.common.ApiResponse;
import com.eighthours.bovinbi.dto.LoginReq;
import com.eighthours.bovinbi.dto.LoginResp;
import com.eighthours.bovinbi.dto.RegisterReq;
import com.eighthours.bovinbi.entity.User;
import com.eighthours.bovinbi.security.UserContext;
import com.eighthours.bovinbi.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/** 认证入口(登录/注册/当前用户):账号体系唯一入口,不再有平行账号路径 */
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/login")
    public ApiResponse<LoginResp> login(@Valid @RequestBody LoginReq req) {
        return ApiResponse.ok(authService.login(req.username(), req.password()));
    }

    @PostMapping("/register")
    public ApiResponse<LoginResp> register(@Valid @RequestBody RegisterReq req) {
        return ApiResponse.ok(authService.register(req.username(), req.password(), req.nickname()));
    }

    @GetMapping("/me")
    public ApiResponse<User> me() {
        return ApiResponse.ok(authService.me(UserContext.uid()));
    }

    /** AI 执行权限划分:AUTO=完全允许 / STEP=每一步过问(仅这两种取值) */
    @PutMapping("/approval-mode")
    public ApiResponse<Void> setApprovalMode(@RequestBody ApprovalModeReq req) {
        authService.setApprovalMode(UserContext.uid(), req.mode());
        return ApiResponse.ok();
    }

    public record ApprovalModeReq(String mode) {
    }
}
