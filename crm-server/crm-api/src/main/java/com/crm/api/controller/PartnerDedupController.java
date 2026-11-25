package com.crm.api.controller;

import com.crm.api.common.ApiResponse;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.PartnerDedupDtos;
import com.crm.api.security.AuthContext;
import com.crm.api.service.PartnerDedupService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/partners")
public class PartnerDedupController {
    private final PartnerDedupService service;

    public PartnerDedupController(PartnerDedupService service) {
        this.service = service;
    }

    @PostMapping("/check-dup")
    public ApiResponse<PartnerDedupDtos.Result> check(@RequestBody(required = false) PartnerDedupDtos.Request request) {
        if (AuthContext.get() == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return ApiResponse.ok(service.check(request == null ? null : request.name(),
                request == null ? null : request.creditCode()));
    }
}
