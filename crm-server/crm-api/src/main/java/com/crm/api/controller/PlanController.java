package com.crm.api.controller;

import com.crm.api.common.ApiResponse;
import com.crm.api.dto.PlanDtos;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.idempotency.IdempotencyHttp;
import com.crm.api.idempotency.IdempotencyService;
import com.crm.api.idempotency.Idempotent;
import com.crm.api.security.RequireRole;
import com.crm.api.security.Role;
import com.crm.api.service.PlanService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/partners/{id}/plan")
public class PlanController {
    private final PlanService service;
    private final IdempotencyHttp idempotency;
    private final ObjectMapper json;

    public PlanController(PlanService service, IdempotencyHttp idempotency, ObjectMapper json) {
        this.service = service;
        this.idempotency = idempotency;
        this.json = json;
    }

    @GetMapping
    public ApiResponse<PlanDtos.Plan> get(@PathVariable Long id) {
        return ApiResponse.ok(service.get(id));
    }

    @PutMapping
    @Idempotent
    @RequireRole({Role.SALES, Role.ADMIN})
    public ResponseEntity<byte[]> put(@PathVariable Long id,
            @RequestBody(required = false) byte[] body, HttpServletRequest request) {
        byte[] raw = body == null ? new byte[0] : body;
        return idempotency.execute(request, raw, () -> service.requireAccessible(id), () -> {
            PlanDtos.Request parsed;
            try {
                parsed = raw.length == 0 ? null : json.readValue(raw, PlanDtos.Request.class);
            } catch (java.io.IOException e) {
                throw new BizException(ErrorCode.PARAM_INVALID, "请求参数格式错误");
            }
            PlanDtos.Saved saved = service.save(id, parsed, request);
            try {
                return new IdempotencyService.Response(200, MediaType.APPLICATION_JSON_VALUE,
                        json.writeValueAsBytes(ApiResponse.ok(saved)));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("响应序列化失败", e);
            }
        });
    }
}
