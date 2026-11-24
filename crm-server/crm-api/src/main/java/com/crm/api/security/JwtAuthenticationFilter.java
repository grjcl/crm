package com.crm.api.security;

import java.io.IOException;
import java.util.Set;

import com.crm.api.common.ApiResponse;
import com.crm.api.common.ErrorCode;
import com.crm.domain.entity.SysUser;
import com.crm.infra.mapper.SysUserMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * JWT 认证过滤器：解析 Bearer access token、校验类型/签名/过期/黑名单/账号状态，写入 {@link AuthContext}。
 *
 * <p>匿名接口在 {@link #shouldNotFilter(HttpServletRequest)} 中放行；其余 /api 请求缺少或使用无效
 * token 时返回 1002。登出接口对已登出的 token 放行，由控制器保证幂等返回 0。</p>
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    private static final String LOGOUT_PATH = "/api/v1/auth/logout";
    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/health");

    private final JwtService jwtService;
    private final TokenBlacklistService blacklistService;
    private final SysUserMapper userMapper;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService,
                                   TokenBlacklistService blacklistService,
                                   SysUserMapper userMapper,
                                   ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.blacklistService = blacklistService;
        this.userMapper = userMapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !path.startsWith("/api/") || PUBLIC_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            writeError(response, ErrorCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
            return;
        }

        String token = header.substring("Bearer ".length()).trim();
        try {
            JwtService.ParsedToken parsed = jwtService.parse(token, JwtService.TYPE_ACCESS);

            if (blacklistService.isBlacklisted(parsed.jti())) {
                if (!LOGOUT_PATH.equals(request.getRequestURI())) {
                    writeError(response, ErrorCode.UNAUTHORIZED.getCode(), "登录已过期，请重新登录");
                    return;
                }
                // 已登出的 access token 再次登出：放行，由控制器幂等返回 0
                AuthContext.set(new AuthContext.AuthUser(parsed.userId(), parsed.role(), parsed.jti(),
                        parsed.issuedAt(), parsed.expiresAt(), true));
            } else {
                if (blacklistService.isUserInvalidated(parsed.userId(), parsed.issuedAt())) {
                    writeError(response, ErrorCode.UNAUTHORIZED.getCode(), "账号已停用");
                    return;
                }

                SysUser user = userMapper.selectById(parsed.userId());
                if (user == null || !"ACTIVE".equals(user.getStatus())) {
                    writeError(response, ErrorCode.UNAUTHORIZED.getCode(), "账号已停用");
                    return;
                }

                AuthContext.set(new AuthContext.AuthUser(user.getId(), user.getRole(), parsed.jti(),
                        parsed.issuedAt(), parsed.expiresAt(), false));
            }
        } catch (JwtException | IllegalArgumentException exception) {
            writeError(response, ErrorCode.UNAUTHORIZED.getCode(), "未登录或登录已过期");
            return;
        } catch (Exception exception) {
            log.error("鉴权过滤器异常", exception);
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getDefaultMessage());
            return;
        }

        try {
            filterChain.doFilter(request, response);
        } finally {
            AuthContext.clear();
        }
    }

    private void writeError(HttpServletResponse response, int code, String message) throws IOException {
        writeError(response, HttpServletResponse.SC_OK, code, message);
    }

    private void writeError(HttpServletResponse response, int httpStatus, int code, String message)
            throws IOException {
        response.setStatus(httpStatus);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.error(code, message)));
    }
}
