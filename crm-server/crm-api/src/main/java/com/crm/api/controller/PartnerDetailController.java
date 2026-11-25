package com.crm.api.controller;

import com.crm.api.common.ApiResponse;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.PartnerDetailDtos;
import com.crm.api.security.RequireRole;
import com.crm.api.security.Role;
import com.crm.api.service.PartnerDetailService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/partners")
@RequireRole({Role.SALES, Role.ADMIN})
public class PartnerDetailController {
    private final PartnerDetailService service;

    public PartnerDetailController(PartnerDetailService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ApiResponse<PartnerDetailDtos.Detail> detail(@PathVariable String id,
            @RequestParam(required = false) String recentFollows) {
        try {
            return ApiResponse.ok(service.get(Long.parseLong(id),
                    recentFollows == null ? null : Integer.valueOf(recentFollows)));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求参数格式错误");
        }
    }
}
