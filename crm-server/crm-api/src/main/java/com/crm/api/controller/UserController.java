package com.crm.api.controller;

import java.util.List;

import com.crm.api.common.ApiResponse;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.UserBrief;
import com.crm.api.security.AuthContext;
import com.crm.api.security.Role;
import com.crm.api.service.UserQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全员列表：登录即可读（SALES 也需要同事姓名才能选协作人/负责人），不限定角色。
 */
@RestController
@RequestMapping("/api/v1")
public class UserController {

    private final UserQueryService userQueryService;

    public UserController(UserQueryService userQueryService) {
        this.userQueryService = userQueryService;
    }

    /**
     * GET /users?enabled=&q=
     *
     * <p>默认只返回 ACTIVE；传 enabled=false 时仅 ADMIN 能看到 DISABLED，SALES 仍按 true 处理。</p>
     */
    @GetMapping("/users")
    public ApiResponse<List<UserBrief>> list(
            @RequestParam(value = "enabled", required = false) Boolean enabled,
            @RequestParam(value = "q", required = false) String q) {
        AuthContext.AuthUser current = AuthContext.get();
        if (current == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        boolean includeDisabled = Boolean.FALSE.equals(enabled) && Role.ADMIN.name().equals(current.role());
        return ApiResponse.ok(userQueryService.list(includeDisabled, q));
    }
}
