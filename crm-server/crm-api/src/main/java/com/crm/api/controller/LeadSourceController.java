package com.crm.api.controller;

import com.crm.api.common.ApiResponse;
import com.crm.api.dto.LeadSourceDtos;
import com.crm.api.security.RequireRole;
import com.crm.api.security.Role;
import com.crm.api.service.LeadSourceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 来源字典增删改（BE-API-050~052）：仅 ADMIN；读取走 GET /meta/dicts。
 */
@RestController
@RequestMapping("/api/v1/lead-sources")
public class LeadSourceController {

    private final LeadSourceService leadSourceService;

    public LeadSourceController(LeadSourceService leadSourceService) {
        this.leadSourceService = leadSourceService;
    }

    @PostMapping
    @RequireRole(Role.ADMIN)
    public ApiResponse<LeadSourceDtos.CreatedSource> create(
            @RequestBody(required = false) LeadSourceDtos.NameRequest body,
            HttpServletRequest request) {
        String name = body == null ? null : body.name();
        return ApiResponse.ok(leadSourceService.create(name, request));
    }

    @PutMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public ApiResponse<LeadSourceDtos.RenamedSource> rename(
            @PathVariable Long id,
            @RequestBody(required = false) LeadSourceDtos.NameRequest body,
            HttpServletRequest request) {
        String name = body == null ? null : body.name();
        return ApiResponse.ok(leadSourceService.rename(id, name, request));
    }

    @DeleteMapping("/{id}")
    @RequireRole(Role.ADMIN)
    public ApiResponse<Void> delete(@PathVariable Long id, HttpServletRequest request) {
        leadSourceService.delete(id, request);
        return ApiResponse.ok();
    }
}
