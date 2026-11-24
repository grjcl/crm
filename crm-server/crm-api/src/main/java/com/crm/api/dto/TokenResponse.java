package com.crm.api.dto;

/**
 * token 刷新出参（login 与 refresh 共用字段）。
 */
public record TokenResponse(String accessToken, String refreshToken, int expiresIn) {
}
