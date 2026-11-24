package com.crm.api.dto;

/**
 * POST /auth/refresh 入参。
 */
public record RefreshRequest(String refreshToken) {
}
