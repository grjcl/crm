package com.crm.api.controller;

import com.crm.api.common.GlobalExceptionHandler;
import com.crm.api.idempotency.IdempotencyHttp;
import com.crm.api.idempotency.IdempotencyService;
import com.crm.api.security.AuthContext;
import com.crm.api.security.JwtAuthenticationFilter;
import com.crm.api.security.JwtService;
import com.crm.api.security.TokenBlacklistService;
import com.crm.infra.mapper.SysUserMapper;
import com.crm.api.security.RoleInterceptor;
import com.crm.api.service.PlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlanControllerTest {
    private JdbcTemplate jdbc;
    private IdempotencyService idempotencyStore;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        idempotencyStore = mock(IdempotencyService.class);
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        mvc = MockMvcBuilders.standaloneSetup(new PlanController(new PlanService(jdbc, json),
                        new IdempotencyHttp(idempotencyStore), json))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new RoleInterceptor()).build();
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(1L, "SALES", "jti", now, now.plusSeconds(60), false));
    }

    @AfterEach
    void cleanup() { AuthContext.clear(); }

    @Test
    void invalidInputsAreRejectedBeforeDatabaseWrites() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L))).thenReturn(List.of(7L));
        when(idempotencyStore.execute(anyLong(), anyString(), anyString(), anyString(), any(byte[].class), any()))
                .thenAnswer(invocation -> invocation.<Supplier<IdempotencyService.Response>>getArgument(5).get());
        String[] invalid = {
                "", "{}", "{\"date\":\"2020-01-01\",\"note\":\"x\"}",
                "{\"date\":\"2020-01-01\",\"note\":\"\",\"version\":0}",
                "{\"date\":\"2020-01-01\",\"note\":\"x\",\"version\":-1}",
                "{\"date\":\"2020-01-01\",\"note\":\"" + "x".repeat(61) + "\",\"version\":0}",
                "{\"date\":\"bad-date\",\"note\":\"x\",\"version\":0}", "{"
        };
        for (String body : invalid) {
            mvc.perform(put("/api/v1/partners/7/plan").header("Idempotency-Key", "test-key")
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(1001));
        }
        verify(jdbc, times(invalid.length)).query(anyString(), any(RowMapper.class), eq(7L));
        verifyNoMoreInteractions(jdbc);
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        AuthContext.clear();
        mvc.perform(put("/api/v1/partners/7/plan").header("Idempotency-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2020-01-01\",\"note\":\"x\",\"version\":0}"))
                .andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(jdbc, idempotencyStore);
    }

    @Test
    void anonymousGetIsRejectedByAuthenticationFilter() throws Exception {
        AuthContext.clear();
        ObjectMapper json = new ObjectMapper().findAndRegisterModules();
        JwtService jwt = mock(JwtService.class);
        TokenBlacklistService blacklist = mock(TokenBlacklistService.class);
        SysUserMapper users = mock(SysUserMapper.class);
        MockMvc filtered = MockMvcBuilders.standaloneSetup(new PlanController(new PlanService(jdbc, json),
                        new IdempotencyHttp(idempotencyStore), json))
                .addFilters(new JwtAuthenticationFilter(jwt, blacklist, users, json)).build();
        filtered.perform(get("/api/v1/partners/7/plan"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(jdbc, idempotencyStore, jwt, blacklist, users);
    }

    @Test
    void duplicateIdempotencyKeysAreRejectedAfterResourceCheck() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L))).thenReturn(List.of(7L));
        mvc.perform(put("/api/v1/partners/7/plan").header("Idempotency-Key", "key1", "key2")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(1001));
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(7L));
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(idempotencyStore);
    }

    @Test
    void malformedIdempotencyKeyIsRejectedByStoreAfterResourceCheck() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L))).thenReturn(List.of(7L));
        when(idempotencyStore.execute(anyLong(), anyString(), anyString(), anyString(), any(byte[].class), any()))
                .thenThrow(new IdempotencyService.Rejected(IdempotencyService.Reason.INVALID_KEY));
        mvc.perform(put("/api/v1/partners/7/plan").header("Idempotency-Key", "bad key")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(jsonPath("$.code").value(1001));
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(7L));
        verifyNoMoreInteractions(jdbc);
        verify(idempotencyStore).execute(eq(1L), eq("bad key"), eq("PUT"),
                eq("/api/v1/partners/7/plan"), any(byte[].class), any());
    }

    @Test
    void missingIdempotencyKeyIsRejectedAfterResourceCheck() throws Exception {
        when(jdbc.query(anyString(), any(RowMapper.class), eq(7L))).thenReturn(List.of(7L));
        mvc.perform(put("/api/v1/partners/7/plan").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"date\":\"2020-01-01\",\"note\":\"x\",\"version\":0}"))
                .andExpect(jsonPath("$.code").value(1001));
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(7L));
        verifyNoMoreInteractions(jdbc);
        verifyNoInteractions(idempotencyStore);
    }
}
