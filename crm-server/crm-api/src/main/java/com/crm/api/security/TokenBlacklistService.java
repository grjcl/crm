package com.crm.api.security;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 登出黑名单与停用即时失效（Redis）。
 *
 * <p>key：</p>
 * <ul>
 *   <li>{@code blacklist:jti:{jti}}：已登出的 token，TTL 为 token 剩余有效期</li>
 *   <li>{@code blacklist:user:{userId}}：账号停用时间戳，早于该时间签发的 token 全部失效</li>
 *   <li>{@code refresh:user:{userId}}：该用户已签发的 refresh jti → 过期时间，用于登出时一并作废</li>
 * </ul>
 *
 * <p>Redis 不可用时降级放行并记 error 日志，不阻断业务。</p>
 */
@Service
public class TokenBlacklistService {

    private static final Logger log = LoggerFactory.getLogger(TokenBlacklistService.class);

    static final String JTI_KEY_PREFIX = "blacklist:jti:";
    static final String USER_KEY_PREFIX = "blacklist:user:";
    static final String REFRESH_KEY_PREFIX = "refresh:user:";

    private final StringRedisTemplate redis;

    public TokenBlacklistService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void blacklistJti(String jti, Instant expiresAt) {
        if (jti == null || expiresAt == null) {
            return;
        }
        long ttlSeconds = Duration.between(Instant.now(), expiresAt).toSeconds();
        if (ttlSeconds <= 0) {
            return;
        }
        try {
            redis.opsForValue().set(JTI_KEY_PREFIX + jti, "1", Duration.ofSeconds(ttlSeconds));
        } catch (Exception exception) {
            log.error("Redis 不可用，token 黑名单写入降级：jti={}", jti, exception);
        }
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(redis.hasKey(JTI_KEY_PREFIX + jti));
        } catch (Exception exception) {
            log.error("Redis 不可用，token 黑名单校验降级放行：jti={}", jti, exception);
            return false;
        }
    }

    /**
     * 记录该用户签发的 refresh token，便于登出时按用户作废。
     */
    public void registerRefreshJti(Long userId, String jti, Instant expiresAt) {
        if (userId == null || jti == null || expiresAt == null) {
            return;
        }
        long ttlSeconds = Duration.between(Instant.now(), expiresAt).toSeconds();
        if (ttlSeconds <= 0) {
            return;
        }
        try {
            String key = REFRESH_KEY_PREFIX + userId;
            redis.opsForHash().put(key, jti, String.valueOf(expiresAt.toEpochMilli()));
            redis.expire(key, Duration.ofSeconds(ttlSeconds));
        } catch (Exception exception) {
            log.error("Redis 不可用，refresh token 索引写入降级：userId={}", userId, exception);
        }
    }

    /**
     * 登出时把该用户已签发的 refresh token 全部加入黑名单。
     */
    public void blacklistUserRefreshTokens(Long userId) {
        if (userId == null) {
            return;
        }
        String key = REFRESH_KEY_PREFIX + userId;
        try {
            Map<Object, Object> entries = redis.opsForHash().entries(key);
            for (Map.Entry<Object, Object> entry : entries.entrySet()) {
                try {
                    Instant expiresAt = Instant.ofEpochMilli(Long.parseLong(String.valueOf(entry.getValue())));
                    blacklistJti(String.valueOf(entry.getKey()), expiresAt);
                } catch (NumberFormatException exception) {
                    log.warn("refresh token 索引值无法解析，忽略：userId={}", userId);
                }
            }
            redis.delete(key);
        } catch (Exception exception) {
            log.error("Redis 不可用，refresh token 登出作废降级：userId={}", userId, exception);
        }
    }

    /**
     * 标记账号停用时间；此后所有 iat 不晚于该时间的 token 失效。
     */
    public void markUserDisabled(Long userId, Instant disabledAt) {
        try {
            redis.opsForValue().set(USER_KEY_PREFIX + userId, String.valueOf(disabledAt.toEpochMilli()));
        } catch (Exception exception) {
            log.error("Redis 不可用，停用时间戳写入降级：userId={}", userId, exception);
        }
    }

    public boolean isUserInvalidated(Long userId, Instant issuedAt) {
        if (userId == null || issuedAt == null) {
            return false;
        }
        try {
            String value = redis.opsForValue().get(USER_KEY_PREFIX + userId);
            if (value == null) {
                return false;
            }
            return issuedAt.toEpochMilli() <= Long.parseLong(value);
        } catch (Exception exception) {
            log.error("Redis 不可用，停用状态校验降级放行：userId={}", userId, exception);
            return false;
        }
    }
}
