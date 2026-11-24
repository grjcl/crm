package com.crm.api.security;

import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 角色校验：方法或类上的 {@link RequireRole} 决定允许的角色，未标注则放行。
 */
@Component
public class RoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        RequireRole requireRole = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (requireRole == null) {
            requireRole = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (requireRole == null) {
            return true;
        }

        AuthContext.AuthUser current = AuthContext.get();
        if (current == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        for (Role role : requireRole.value()) {
            if (role.name().equals(current.role())) {
                return true;
            }
        }
        throw new BizException(ErrorCode.FORBIDDEN, "没有权限执行该操作", 403);
    }
}
