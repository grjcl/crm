package com.crm.api.dto;

/**
 * POST /auth/login 入参。
 */
public record LoginRequest(String phone, String password) {
}
