package com.crm.api.controller;

import com.crm.api.common.ApiResponse;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.LoginRequest;
import com.crm.api.dto.LoginResponse;
import com.crm.api.dto.RefreshRequest;
import com.crm.api.dto.TokenResponse;
import com.crm.api.security.AuthContext;
import com.crm.api.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权接口：登录、刷新、登出（匿名/登录见各方法说明）。
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody(required = false) LoginRequest request,
                                            HttpServletRequest httpRequest) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_INVALID);
        }
        return ApiResponse.ok(authService.login(request.phone(), request.password(), httpRequest));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenResponse> refresh(@RequestBody(required = false) RefreshRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.ok(authService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest httpRequest) {
        AuthContext.AuthUser current = AuthContext.get();
        if (current == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        if (current.alreadyLoggedOut()) {
            return ApiResponse.ok();
        }
        authService.logout(current.userId(), current.jti(), current.expiresAt(), httpRequest);
        return ApiResponse.ok();
    }
}
