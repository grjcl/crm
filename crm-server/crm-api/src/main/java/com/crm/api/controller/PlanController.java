package com.crm.api.controller;

import com.crm.api.common.ApiResponse;
import com.crm.api.dto.PlanDtos;
import com.crm.api.service.PlanService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/partners/{id}/plan")
public class PlanController {
    private final PlanService service;

    public PlanController(PlanService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<PlanDtos.Plan> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping
    public ApiResponse<PlanDtos.Saved> put(@PathVariable Long id,
            @RequestBody(required = false) PlanDtos.Request body, HttpServletRequest request) {
        return ApiResponse.ok(service.save(id, body, request));
    }
}
