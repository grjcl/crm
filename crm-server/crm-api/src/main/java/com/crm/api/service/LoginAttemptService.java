package com.crm.api.service;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * 登录失败计数与 15 分钟锁定（Redis {@code login:fail:{phone}}）。
 *
 * <p>只在「账号存在且密码错误」时调用 {@link #recordFailure(String)}，账号不存在不创建计数，
 * 避免通过锁定状态探测账号是否存在。Redis 不可用时降级为不锁定并记 error 日志。</p>
 */
@Service
public class LoginAttemptService {

    private static final Logger log = LoggerFactory.getLogger(LoginAttemptService.class);

    static final String KEY_PREFIX = "login:fail:";
    static final int MAX_FAILURES = 5;
    static final Duration LOCK_TTL = Duration.ofMinutes(15);

    private final StringRedisTemplate redis;

    public LoginAttemptService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public boolean isLocked(String phone) {
        try {
            String value = redis.opsForValue().get(KEY_PREFIX + phone);
            return value != null && Long.parseLong(value) >= MAX_FAILURES;
        } catch (Exception exception) {
            log.error("Redis 不可用，登录锁定校验降级放行：phone={}", phone, exception);
            return false;
        }
    }

    /**
     * 记录一次密码错误，返回累计失败次数；Redis 异常时返回 0。
     */
    public long recordFailure(String phone) {
        try {
            String key = KEY_PREFIX + phone;
            Long count = redis.opsForValue().increment(key);
            redis.expire(key, LOCK_TTL);
            return count == null ? 0L : count;
        } catch (Exception exception) {
            log.error("Redis 不可用，登录失败计数降级：phone={}", phone, exception);
            return 0L;
        }
    }

    public void clearFailures(String phone) {
        try {
            redis.delete(KEY_PREFIX + phone);
        } catch (Exception exception) {
            log.error("Redis 不可用，登录失败计数清零降级：phone={}", phone, exception);
        }
    }
}
