package com.crm.api.controller;

import com.crm.api.common.ApiResponse;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.PartnerCreateDtos;
import com.crm.api.idempotency.IdempotencyHttp;
import com.crm.api.idempotency.IdempotencyService;
import com.crm.api.idempotency.Idempotent;
import com.crm.api.security.AuthContext;
import com.crm.api.security.RequireRole;
import com.crm.api.security.Role;
import com.crm.api.service.PartnerCreateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/partners")
public class PartnerCreateController {
    private final PartnerCreateService service;
    private final IdempotencyHttp idempotency;
    private final ObjectMapper json;
    public PartnerCreateController(PartnerCreateService service, IdempotencyHttp idempotency, ObjectMapper json) {
        this.service = service; this.idempotency = idempotency; this.json = json;
    }
    @PostMapping
    @Idempotent
    @RequireRole({Role.SALES, Role.ADMIN})
    public ResponseEntity<byte[]> create(@RequestBody(required = false) byte[] body, HttpServletRequest request) {
        byte[] raw = body == null ? new byte[0] : body;
        return idempotency.execute(request, raw, () -> {
            var user = AuthContext.get();
            if (user == null) throw new BizException(ErrorCode.UNAUTHORIZED);
            if (!"SALES".equals(user.role()) && !"ADMIN".equals(user.role()))
                throw new BizException(ErrorCode.FORBIDDEN, "没有权限执行该操作", 403);
        }, () -> {
            PartnerCreateDtos.Request parsed;
            try { parsed = json.readValue(raw, PartnerCreateDtos.Request.class); }
            catch (java.io.IOException e) { throw new BizException(ErrorCode.PARAM_INVALID, "请求参数格式错误"); }
            var result = service.create(parsed, request);
            try { return new IdempotencyService.Response(200, MediaType.APPLICATION_JSON_VALUE,
                    json.writeValueAsBytes(ApiResponse.ok(result))); }
            catch (java.io.IOException e) { throw new IllegalStateException("响应序列化失败", e); }
        });
    }
}
