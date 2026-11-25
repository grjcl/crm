package com.crm.api.controller;

import com.crm.api.common.ApiResponse;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.common.PageQuery;
import com.crm.api.common.PageResult;
import com.crm.api.dto.PartnerListDtos;
import com.crm.api.security.AuthContext;
import com.crm.api.service.PartnerListService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/partners")
public class PartnerListController {
    private final PartnerListService service;

    public PartnerListController(PartnerListService service) { this.service = service; }

    @GetMapping
    public ApiResponse<PageResult<PartnerListDtos.Item>> list(
            @RequestParam(required = false) String tag, @RequestParam(required = false) String city,
            @RequestParam(required = false) String level, @RequestParam(required = false) String status,
            @RequestParam(required = false) String type, @RequestParam(required = false) String q,
            @RequestParam(required = false) String owner, @RequestParam(required = false) String page,
            @RequestParam(required = false) String size) {
        if (AuthContext.get() == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        try {
            PageQuery pagination = PageQuery.of(page == null ? null : Integer.valueOf(page),
                    size == null ? null : Integer.valueOf(size));
            return ApiResponse.ok(service.list(tag, city, level, status, type, q,
                    owner == null ? null : Long.valueOf(owner), pagination));
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求参数格式错误");
        }
    }
}
