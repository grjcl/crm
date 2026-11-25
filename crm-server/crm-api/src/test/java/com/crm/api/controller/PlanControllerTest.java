package com.crm.api.controller;

import com.crm.api.common.GlobalExceptionHandler;
import com.crm.api.security.AuthContext;
import com.crm.api.security.RoleInterceptor;
import com.crm.api.service.PlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class PlanControllerTest {
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        jdbc = mock(JdbcTemplate.class);
        mvc = MockMvcBuilders.standaloneSetup(new PlanController(new PlanService(jdbc, new ObjectMapper())))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new RoleInterceptor()).build();
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(1L, "SALES", "jti", now, now.plusSeconds(60), false));
    }

    @AfterEach
    void cleanup() { AuthContext.clear(); }

    @Test
    void invalidInputsAreRejectedBeforeDatabaseWrites() throws Exception {
        String[] invalid = {
                "{}", "{\"date\":\"2020-01-01\",\"note\":\"x\"}",
                "{\"date\":\"2020-01-01\",\"note\":\"\",\"version\":0}",
                "{\"date\":\"2020-01-01\",\"note\":\"x\",\"version\":-1}",
                "{\"date\":\"2020-01-01\",\"note\":\"" + "x".repeat(61) + "\",\"version\":0}",
                "{\"date\":\"bad-date\",\"note\":\"x\",\"version\":0}"
        };
        for (String body : invalid) {
            mvc.perform(put("/api/v1/partners/7/plan").contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(jsonPath("$.code").value(1001));
        }
        verifyNoInteractions(jdbc);
    }

    @Test
    void anonymousRequestsAreRejected() throws Exception {
        AuthContext.clear();
        mvc.perform(put("/api/v1/partners/7/plan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"2020-01-01\",\"note\":\"x\",\"version\":0}"))
                .andExpect(jsonPath("$.code").value(1002));
        verifyNoInteractions(jdbc);
    }
}
