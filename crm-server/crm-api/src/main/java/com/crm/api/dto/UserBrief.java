package com.crm.api.dto;

/**
 * GET /users 出参项：选人用最小字段集，手机号已脱敏。
 *
 * <p>不得包含 {@code passwordHash} / 明文手机号 / {@code lastLoginAt}（契约 A1 决策）。</p>
 */
public record UserBrief(Long id, String name, String dept, String employeeNo,
                        String role, String phoneMasked) {
}
