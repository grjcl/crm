package com.crm.api.dto;

/**
 * POST /auth/login 出参。
 */
public record LoginResponse(String accessToken, String refreshToken, int expiresIn, UserSummary user) {
}
