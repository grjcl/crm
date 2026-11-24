package com.crm.api.controller;

import com.crm.api.common.ApiResponse;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.MeResponse;
import com.crm.api.security.AuthContext;
import com.crm.api.service.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 当前用户信息：需登录，不限定角色。
 */
@RestController
@RequestMapping("/api/v1")
public class MeController {

    private final AuthService authService;

    public MeController(AuthService authService) {
        this.authService = authService;
    }

    @GetMapping("/me")
    public ApiResponse<MeResponse> me() {
        AuthContext.AuthUser current = AuthContext.get();
        if (current == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.ok(authService.getMe(current.userId()));
    }
}
