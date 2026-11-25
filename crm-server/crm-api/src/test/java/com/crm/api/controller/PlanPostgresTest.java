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
import java.time.LocalDate;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import com.crm.api.common.BizException;
import com.crm.api.dto.PlanDtos;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.mock.web.MockHttpServletRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import com.crm.api.idempotency.IdempotencyHttp;
import com.crm.api.idempotency.IdempotencyService;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in PostgreSQL tests in a uniquely named, disposable schema. */
class PlanPostgresTest {
    private String schema;
    private boolean schemaCreated;
    private String url;
    private String user;
    private String password;
    private JdbcTemplate jdbc;
    private PlanService service;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        url = System.getenv("CRM_TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "Set CRM_TEST_DATABASE_URL for PostgreSQL tests");
        user = System.getenv("CRM_TEST_DATABASE_USER");
        password = System.getenv("CRM_TEST_DATABASE_PASSWORD");
        schema = "t_plan_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = connect(); var statement = c.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            schemaCreated = true;
        }
        DataSource ds = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection c = connect();
                try { c.setSchema(schema); return c; }
                catch (SQLException e) { c.close(); throw e; }
            }
            @Override public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("Explicit credentials unsupported");
            }
        };
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE org (id bigint PRIMARY KEY, deleted_at timestamptz)");
        jdbc.execute("CREATE TABLE sys_user (id bigint PRIMARY KEY, name varchar(30))");
        jdbc.execute("CREATE TABLE follow_plan (id bigserial PRIMARY KEY, org_id bigint UNIQUE NOT NULL, plan_date date NOT NULL, note varchar(60) NOT NULL, owner_id bigint NOT NULL, version int NOT NULL, created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now())");
        jdbc.execute("CREATE TABLE audit_log (actor_id bigint, action text, entity text, entity_id bigint, diff jsonb, ip text, ua text)");
        java.nio.file.Path root = java.nio.file.Path.of("").toAbsolutePath();
        while (root != null && !java.nio.file.Files.exists(root.resolve("shared/db/V4__idempotency_record.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot locate idempotency migration");
        jdbc.execute(java.nio.file.Files.readString(root.resolve("shared/db/V4__idempotency_record.sql")));
        jdbc.update("INSERT INTO org VALUES (7, null), (8, now())");
        jdbc.update("INSERT INTO sys_user VALUES (1, '销售')");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var txManager = new DataSourceTransactionManager(ds);
        ProxyFactory proxy = new ProxyFactory(new PlanService(jdbc, mapper));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(txManager, new AnnotationTransactionAttributeSource()));
        service = (PlanService) proxy.getProxy();
        var idempotency = new IdempotencyHttp(new IdempotencyService(jdbc, txManager));
        mvc = MockMvcBuilders.standaloneSetup(new PlanController(service, idempotency, mapper))
                .setMessageConverters(new org.springframework.http.converter.ByteArrayHttpMessageConverter(),
                        new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new RoleInterceptor()).build();
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(1L, "SALES", "jti", now, now.plusSeconds(60), false));
    }

    @AfterEach
    void cleanup() throws Exception {
        AuthContext.clear();
        if (schemaCreated) {
            if (!schema.matches("t_plan_[0-9a-f]{32}")) throw new IllegalStateException("Invalid test schema name");
            try (Connection c = connect(); var statement = c.createStatement()) {
                statement.execute("DROP SCHEMA " + schema + " CASCADE");
            }
        }
    }

    private Connection connect() throws SQLException {
        return user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password);
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
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE diff->'before' = 'null'::jsonb AND diff->'after'->>'note' = '补录' AND diff->'after'->>'version' = '1'", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log WHERE diff->'before'->>'note' = '补录' AND diff->'after'->>'note' = '更新' AND diff->'after'->>'version' = '2'", Integer.class));
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

    @Test
    void replayConflictAndRevokedPartnerNeverReturnCachedSuccess() throws Exception {
        String key = "replay-key";
        String first = save(7, "2020-01-01", "初始", 0, key).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        String replay = save(7, "2020-01-01", "初始", 0, key).andExpect(jsonPath("$.code").value(0))
                .andReturn().getResponse().getContentAsString();
        assertEquals(first, replay);
        save(7, "2020-01-01", "不同", 0, key).andExpect(jsonPath("$.code").value(3001));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM follow_plan", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
        jdbc.update("UPDATE org SET deleted_at=now() WHERE id=7");
        save(7, "2020-01-01", "初始", 0, key).andExpect(jsonPath("$.code").value(1001));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
    }

    @Test
    void auditFailureRollsBackPlanAndIdempotencyClaim() throws Exception {
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT t_reject_audit CHECK (actor_id < 0)");
        save(7, "2020-01-01", "初始", 0, "rollback-key").andExpect(status().isInternalServerError());
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM follow_plan", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
        assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM idempotency_record", Integer.class));
        jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT t_reject_audit");
        save(7, "2020-01-01", "初始", 0, "rollback-key").andExpect(jsonPath("$.code").value(0));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
    }

    @Test
    void concurrentIdenticalRequestsReplayOneWrite() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 2; i++) {
                tasks.add(executor.submit(() -> {
                    Instant now = Instant.now();
                    AuthContext.set(new AuthContext.AuthUser(1L, "SALES", "jti", now, now.plusSeconds(60), false));
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                        return save(7, "2020-01-01", "并发", 0, "concurrent-key")
                                .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
                    } finally { AuthContext.clear(); }
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                assertEquals(tasks.get(0).get(15, TimeUnit.SECONDS), tasks.get(1).get(15, TimeUnit.SECONDS));
            } finally { start.countDown(); }
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM follow_plan", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM idempotency_record", Integer.class));
    }

    @Test
    void concurrentSameVersionHasOneWinnerAndOneAudit() throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                final int index = i;
                tasks.add(executor.submit(() -> {
                    Instant now = Instant.now();
                    AuthContext.set(new AuthContext.AuthUser(1L, "SALES", "jti", now, now.plusSeconds(60), false));
                    try {
                        ready.countDown();
                        if (!start.await(10, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                        try {
                            service.save(7L, new PlanDtos.Request(LocalDate.of(2020, 1, 1), "write" + index, 0), new MockHttpServletRequest());
                            return 0;
                        } catch (BizException e) { return e.getCode(); }
                    } finally { AuthContext.clear(); }
                }));
            }
            try {
                assertTrue(ready.await(10, TimeUnit.SECONDS));
                start.countDown();
                var results = java.util.List.of(tasks.get(0).get(15, TimeUnit.SECONDS), tasks.get(1).get(15, TimeUnit.SECONDS));
                assertEquals(1, results.stream().filter(code -> code == 0).count());
                assertEquals(1, results.stream().filter(code -> code == 4001).count());
            } finally { start.countDown(); }
        }
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM follow_plan", Integer.class));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM audit_log", Integer.class));
    }

    private ResultActions save(int id, String date, String note, int version) throws Exception {
        return save(id, date, note, version, UUID.randomUUID().toString());
    }

    private ResultActions save(int id, String date, String note, int version, String key) throws Exception {
        return mvc.perform(put("/api/v1/partners/" + id + "/plan").header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"date\":\"" + date + "\",\"note\":\"" + note + "\",\"version\":" + version + "}"));
    }
}
