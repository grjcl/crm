package com.crm.api.security;

import java.time.Instant;

/**
 * 当前请求的登录用户上下文，由 {@link JwtAuthenticationFilter} 写入、请求结束后清理。
 */
public final class AuthContext {

    private static final ThreadLocal<AuthUser> CURRENT = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(AuthUser user) {
        CURRENT.set(user);
    }

    public static AuthUser get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /**
     * 已认证用户快照。
     *
     * @param alreadyLoggedOut 该 access token 已登出（用于登出接口幂等返回 0）
     */
    public record AuthUser(Long userId, String role, String jti,
                           Instant issuedAt, Instant expiresAt, boolean alreadyLoggedOut) {
    }
}
