package com.crm.api.service;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.regex.Pattern;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.common.PhoneMasker;
import com.crm.api.dto.LoginResponse;
import com.crm.api.dto.MeResponse;
import com.crm.api.dto.TokenResponse;
import com.crm.api.dto.UserSummary;
import com.crm.api.security.JwtService;
import com.crm.api.security.TokenBlacklistService;
import com.crm.domain.entity.AuditLog;
import com.crm.domain.entity.SysUser;
import com.crm.infra.mapper.AuditLogMapper;
import com.crm.infra.mapper.SysUserMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 登录、刷新、登出与当前用户信息。
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern PASSWORD_PATTERN =
            Pattern.compile("^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,20}$");
    private static final DateTimeFormatter OFFSET_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssXXX");
    private static final ZoneOffset DEFAULT_OFFSET = ZoneOffset.ofHours(8);

    private final SysUserMapper userMapper;
    private final AuditLogMapper auditLogMapper;
    private final JwtService jwtService;
    private final TokenBlacklistService blacklistService;
    private final LoginAttemptService loginAttemptService;
    private final BCryptPasswordEncoder passwordEncoder;

    public AuthService(SysUserMapper userMapper,
                       AuditLogMapper auditLogMapper,
                       JwtService jwtService,
                       TokenBlacklistService blacklistService,
                       LoginAttemptService loginAttemptService,
                       BCryptPasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.auditLogMapper = auditLogMapper;
        this.jwtService = jwtService;
        this.blacklistService = blacklistService;
        this.loginAttemptService = loginAttemptService;
        this.passwordEncoder = passwordEncoder;
    }

    @Transactional
    public LoginResponse login(String phone, String password, HttpServletRequest request) {
        validateLoginRequest(phone, password);

        SysUser user = userMapper.selectOne(new LambdaQueryWrapper<SysUser>()
                .eq(SysUser::getPhone, phone));

        if (user == null) {
            throw new BizException(ErrorCode.ACCOUNT_NOT_OPENED, "手机号未开通");
        }
        if (!"ACTIVE".equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_NOT_OPENED, "账号已停用");
        }
        if (loginAttemptService.isLocked(phone)) {
            throw new BizException(ErrorCode.ACCOUNT_LOCKED);
        }

        if (!passwordEncoder.matches(password, user.getPasswordHash())) {
            long failures = loginAttemptService.recordFailure(phone);
            if (failures >= LoginAttemptService.MAX_FAILURES) {
                throw new BizException(ErrorCode.ACCOUNT_LOCKED);
            }
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }

        loginAttemptService.clearFailures(phone);
        JwtService.TokenPair tokens = jwtService.issueTokens(user.getId(), user.getRole());
        blacklistService.registerRefreshJti(user.getId(), tokens.refresh().jti(), tokens.refresh().expiresAt());

        SysUser patch = new SysUser();
        patch.setId(user.getId());
        patch.setLastLoginAt(OffsetDateTime.now());
        userMapper.updateById(patch);

        writeAudit(user.getId(), "LOGIN", user.getId(), request);

        return new LoginResponse(
                tokens.access().value(),
                tokens.refresh().value(),
                (int) jwtService.accessTtlSeconds(),
                toSummary(user));
    }

    public TokenResponse refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        JwtService.ParsedToken parsed;
        try {
            parsed = jwtService.parse(refreshToken, JwtService.TYPE_REFRESH);
        } catch (JwtException | IllegalArgumentException exception) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        if (blacklistService.isBlacklisted(parsed.jti())) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }

        SysUser user = userMapper.selectById(parsed.userId());
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            blacklistService.blacklistJti(parsed.jti(), parsed.expiresAt());
            throw new BizException(ErrorCode.ACCOUNT_NOT_OPENED, "账号已停用");
        }

        JwtService.TokenPair tokens = jwtService.issueTokens(user.getId(), user.getRole());
        blacklistService.registerRefreshJti(user.getId(), tokens.refresh().jti(), tokens.refresh().expiresAt());
        return new TokenResponse(
                tokens.access().value(),
                tokens.refresh().value(),
                (int) jwtService.accessTtlSeconds());
    }

    public void logout(Long userId, String accessJti, java.time.Instant accessExpiresAt,
                       HttpServletRequest request) {
        blacklistService.blacklistJti(accessJti, accessExpiresAt);
        blacklistService.blacklistUserRefreshTokens(userId);
        writeAudit(userId, "LOGOUT", userId, request);
    }

    public MeResponse getMe(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null || !"ACTIVE".equals(user.getStatus())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "账号已停用");
        }
        return new MeResponse(
                user.getId(),
                user.getName(),
                PhoneMasker.mask(user.getPhone()),
                user.getEmployeeNo(),
                user.getDept(),
                user.getRole(),
                null,
                formatLastLoginAt(user.getLastLoginAt()));
    }

    private void validateLoginRequest(String phone, String password) {
        if (phone == null || !PHONE_PATTERN.matcher(phone).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请输入正确的手机号");
        }
        if (password == null || !PASSWORD_PATTERN.matcher(password).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请输入密码");
        }
    }

    private UserSummary toSummary(SysUser user) {
        return new UserSummary(
                user.getId(),
                user.getName(),
                PhoneMasker.mask(user.getPhone()),
                user.getEmployeeNo(),
                user.getDept(),
                user.getRole());
    }

    private String formatLastLoginAt(OffsetDateTime lastLoginAt) {
        if (lastLoginAt == null) {
            return null;
        }
        return lastLoginAt.withOffsetSameInstant(DEFAULT_OFFSET).format(OFFSET_TIME_FORMATTER);
    }

    private void writeAudit(Long actorId, String action, Long entityId, HttpServletRequest request) {
        AuditLog auditLog = new AuditLog();
        auditLog.setActorId(actorId);
        auditLog.setAction(action);
        auditLog.setEntity("sys_user");
        auditLog.setEntityId(entityId);
        if (request != null) {
            auditLog.setIp(clientIp(request));
            String ua = request.getHeader("User-Agent");
            auditLog.setUa(ua != null && ua.length() > 200 ? ua.substring(0, 200) : ua);
        }
        auditLogMapper.insert(auditLog);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
