package com.crm.api.dto;

/**
 * GET /me 出参；avatarUrl 一期恒为 null，lastLoginAt 为 ISO8601 +08:00。
 */
public record MeResponse(Long id, String name, String phone, String employeeNo,
                         String dept, String role, String avatarUrl, String lastLoginAt) {
}
