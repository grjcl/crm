package com.crm.api.controller;

import com.crm.api.common.GlobalExceptionHandler;
import com.crm.api.security.AuthContext;
import com.crm.api.security.RoleInterceptor;
import com.crm.api.service.PlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in PostgreSQL tests; connection-local temporary tables avoid changes to shared data. */
class PlanPostgresTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        String url = System.getenv("CRM_TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "Set CRM_TEST_DATABASE_URL for PostgreSQL tests");
        String user = System.getenv("CRM_TEST_DATABASE_USER");
        connection = user == null ? DriverManager.getConnection(url) :
                DriverManager.getConnection(url, user, System.getenv("CRM_TEST_DATABASE_PASSWORD"));
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("CREATE TEMP TABLE org (id bigint PRIMARY KEY, deleted_at timestamptz)");
        jdbc.execute("CREATE TEMP TABLE sys_user (id bigint PRIMARY KEY, name varchar(30))");
        jdbc.execute("CREATE TEMP TABLE follow_plan (id bigserial PRIMARY KEY, org_id bigint UNIQUE NOT NULL, plan_date date NOT NULL, note varchar(60) NOT NULL, owner_id bigint NOT NULL, version int NOT NULL, created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now())");
        jdbc.execute("CREATE TEMP TABLE audit_log (actor_id bigint, action text, entity text, entity_id bigint, diff jsonb, ip text, ua text)");
        jdbc.update("INSERT INTO org VALUES (7, null), (8, now())");
        jdbc.update("INSERT INTO sys_user VALUES (1, '销售')");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        mvc = MockMvcBuilders.standaloneSetup(new PlanController(new PlanService(jdbc, mapper)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new RoleInterceptor()).build();
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(1L, "SALES", "jti", now, now.plusSeconds(60), false));
    }

    @AfterEach
    void cleanup() throws Exception {
        AuthContext.clear();
        if (connection != null) connection.close();
    }

    @Test
    void emptyPlanThenCreateUpdateAndStaleConflict() throws Exception {
        mvc.perform(get("/api/v1/partners/7/plan")).andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data").value((Object) null));
        save(7, "2020-01-01", "补录", 0).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.orgId").value(7)).andExpect(jsonPath("$.data.version").value(1));
        mvc.perform(get("/api/v1/partners/7/plan")).andExpect(jsonPath("$.data.date").value("2020-01-01"))
                .andExpect(jsonPath("$.data.note").value("补录")).andExpect(jsonPath("$.data.owner.id").value(1))
                .andExpect(jsonPath("$.data.owner.name").value("销售")).andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.updatedAt").exists());
        save(7, "2020-01-02", "更新", 1).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.version").value(2));
        save(7, "2020-01-03", "旧版本", 1).andExpect(jsonPath("$.code").value(4001));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM follow_plan", Integer.class));
        assertEquals("更新", jdbc.queryForObject("SELECT note FROM follow_plan WHERE org_id=7", String.class));
        assertEquals(2, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE action='UPDATE' AND entity='follow_plan'", Integer.class));
    }

    @Test
    void missingAndDeletedPartnersAreRejectedAndNonzeroInitialVersionConflicts() throws Exception {
        for (int id : new int[]{8, 9}) {
            mvc.perform(get("/api/v1/partners/" + id + "/plan")).andExpect(jsonPath("$.code").value(1001));
            save(id, "2020-01-01", "测试", 0).andExpect(jsonPath("$.code").value(1001));
        }
        save(7, "2020-01-01", "测试", 1).andExpect(jsonPath("$.code").value(4001));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM follow_plan", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
    }

    private ResultActions save(int id, String date, String note, int version) throws Exception {
        return mvc.perform(put("/api/v1/partners/" + id + "/plan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + date + "\",\"note\":\"" + note + "\",\"version\":" + version + "}"));
    }
}
