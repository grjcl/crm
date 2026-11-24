package com.crm.api.security;

import java.time.Instant;

import com.crm.api.common.BizException;
import com.crm.api.common.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.HandlerMethod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RoleInterceptorTest {

    private final RoleInterceptor interceptor = new RoleInterceptor();

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void salesCannotAccessAdminEndpoint() throws NoSuchMethodException {
        AuthContext.set(user("SALES"));

        BizException exception = assertThrows(BizException.class,
                () -> interceptor.preHandle(null, null, handler("adminOnly")));

        assertEquals(1003, exception.getCode());
        assertEquals(403, exception.getHttpStatus());
    }

    @Test
    void salesRequestReturnsHttp403WithCode1003() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(interceptor)
                .build();
        AuthContext.set(user("SALES"));

        mockMvc.perform(MockMvcRequestBuilders.get("/probe/admin"))
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1003));
    }

    @Test
    void adminCanAccessAdminEndpoint() throws NoSuchMethodException {
        AuthContext.set(user("ADMIN"));

        assertTrue(interceptor.preHandle(null, null, handler("adminOnly")));
    }

    @Test
    void endpointWithoutAnnotationAllowsAnyRole() throws NoSuchMethodException {
        AuthContext.set(user("SALES"));

        assertTrue(interceptor.preHandle(null, null, handler("open")));
    }

    private HandlerMethod handler(String methodName) throws NoSuchMethodException {
        Probe probe = new Probe();
        return new HandlerMethod(probe, Probe.class.getMethod(methodName));
    }

    private AuthContext.AuthUser user(String role) {
        Instant now = Instant.now();
        return new AuthContext.AuthUser(1L, role, "jti", now, now.plusSeconds(60), false);
    }

    static class Probe {

        @RequireRole(Role.ADMIN)
        public void adminOnly() {
        }

        public void open() {
        }
    }

    @RestController
    static class ProbeController {

        @GetMapping("/probe/admin")
        @RequireRole(Role.ADMIN)
        public String admin() {
            return "ok";
        }
    }
}
