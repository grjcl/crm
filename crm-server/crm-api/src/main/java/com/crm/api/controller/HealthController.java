package com.crm.api.controller;

import java.util.LinkedHashMap;
import java.util.Map;

import com.crm.api.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查：本任务唯一的接口。
 */
@RestController
@RequestMapping("/api/v1")
public class HealthController {

    @GetMapping("/health")
    public ApiResponse<Map<String, String>> health() {
        Map<String, String> data = new LinkedHashMap<>();
        data.put("status", "UP");
        return ApiResponse.ok(data);
    }
}
