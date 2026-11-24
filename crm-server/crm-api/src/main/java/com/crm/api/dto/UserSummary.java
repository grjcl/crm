package com.crm.api.dto;

/**
 * 登录返回的用户摘要（手机号已脱敏）。
 */
public record UserSummary(Long id, String name, String phone, String employeeNo, String dept, String role) {
}
