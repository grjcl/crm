package com.crm.api.controller;

import com.crm.api.common.GlobalExceptionHandler;
import com.crm.api.idempotency.IdempotencyHttp;
import com.crm.api.idempotency.IdempotencyService;
import com.crm.api.security.AuthContext;
import com.crm.api.security.RoleInterceptor;
import com.crm.api.service.PartnerCreateService;
import com.crm.api.service.PartnerDedupService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.ByteArrayHttpMessageConverter;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

import javax.sql.DataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Opt-in integration tests; all writes are isolated in a disposable test schema. */
class PartnerCreatePostgresTest {
    private String url, user, password, schema;
    private boolean schemaCreated;
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setup() throws Exception {
        url = System.getenv("CRM_TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "Set CRM_TEST_DATABASE_URL to run PostgreSQL tests");
        user = System.getenv("CRM_TEST_DATABASE_USER");
        password = System.getenv("CRM_TEST_DATABASE_PASSWORD");
        schema = "t_partner_create_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection c = connect(); var s = c.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
            schemaCreated = true;
        }
        DataSource ds = new AbstractDataSource() {
            @Override public Connection getConnection() throws SQLException {
                Connection c = connect();
                try (var s = c.createStatement()) { s.execute("SET search_path TO " + schema); }
                catch (SQLException e) { c.close(); throw e; }
                return c;
            }
            @Override public Connection getConnection(String u, String p) throws SQLException { throw new SQLException("unsupported"); }
        };
        jdbc = new JdbcTemplate(ds);
        // Keep table lookup schema-local while exposing the shared pg_trgm function.
        jdbc.execute("CREATE FUNCTION similarity(text, text) RETURNS real LANGUAGE sql IMMUTABLE STRICT "
                + "AS 'SELECT public.similarity($1, $2)'");
        Path root = Path.of("").toAbsolutePath();
        while (root != null && !Files.exists(root.resolve("shared/db/V1__init.sql"))) root = root.getParent();
        if (root == null) throw new IllegalStateException("Cannot locate migrations");
        String ddl = Files.readString(root.resolve("shared/db/V1__init.sql"));
        for (String table : new String[]{"sys_user", "org_tag", "org", "org_invoice", "org_supply", "org_tag_rel", "audit_log"}) {
            int start = ddl.indexOf("CREATE TABLE " + table + " (");
            int end = ddl.indexOf("\n);", start);
            jdbc.execute(ddl.substring(start, end + 3));
        }
        int indexStart = ddl.indexOf("CREATE UNIQUE INDEX uk_org_credit_code");
        jdbc.execute(ddl.substring(indexStart, ddl.indexOf(';', indexStart) + 1));
        jdbc.execute(Files.readString(root.resolve("shared/db/V4__idempotency_record.sql")));
        jdbc.execute(Files.readString(root.resolve("shared/db/V5__org_settlement_contact.sql")));
        jdbc.update("INSERT INTO sys_user (id,name,phone,role,password_hash) VALUES (1,'销售','13800000001','SALES','x'),(2,'管理员','13800000002','ADMIN','x'),(3,'销售二','13800000003','SALES','x')");
        jdbc.update("INSERT INTO org_tag (id,name,enabled) VALUES (1,'分销',true),(2,'供应',true),(3,'停用',false)");
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        var tx = new DataSourceTransactionManager(ds);
        var proxy = new ProxyFactory(new PartnerCreateService(jdbc, mapper, new PartnerDedupService(jdbc)));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(tx, new AnnotationTransactionAttributeSource()));
        var service = (PartnerCreateService) proxy.getProxy();
        mvc = MockMvcBuilders.standaloneSetup(new PartnerCreateController(service, new IdempotencyHttp(new IdempotencyService(jdbc, tx)), mapper))
                .setMessageConverters(new ByteArrayHttpMessageConverter(), new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new RoleInterceptor()).build();
        login(1, "SALES");
    }

    @AfterEach
    void cleanup() throws Exception {
        AuthContext.clear();
        if (schemaCreated) {
            if (!schema.matches("t_partner_create_[0-9a-f]{32}")) throw new IllegalStateException("Invalid schema");
            try (Connection c = connect(); var s = c.createStatement()) { s.execute("DROP SCHEMA " + schema + " CASCADE"); }
        }
    }

    @Test
    void createsWithDefaultsAndSalesCannotAssignOthers() throws Exception {
        create("{\"name\":\"中航商旅服务有限公司\",\"tags\":[\"分销\"],\"ownerId\":3,\"collaboratorIds\":[3]}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.name").value("中航商旅服务有限公司"))
                .andExpect(jsonPath("$.data.status").value("潜在"));
        assertEquals("中航商旅", jdbc.queryForObject("SELECT short_name FROM org", String.class));
        assertEquals(1L, jdbc.queryForObject("SELECT owner_id FROM org", Long.class));
        assertEquals(0, jdbc.queryForObject("SELECT cardinality(collaborator_ids) FROM org", Integer.class));
        assertEquals("商旅分销", jdbc.queryForObject("SELECT type FROM org", String.class));
        assertEquals("重点", jdbc.queryForObject("SELECT level FROM org", String.class));
        assertEquals(1, count("org_tag_rel"));
        assertEquals(1, count("audit_log"));
    }

    @Test
    void adminCanAssignAndSaveOptionalChildren() throws Exception {
        login(2, "ADMIN");
        create("{\"name\":\"资源供应企业\",\"tags\":[\"供应\",\"分销\"],\"ownerId\":3,\"collaboratorIds\":[1],\"foundDate\":\"2020-03\",\"invoice\":{\"title\":\"开票企业\",\"account\":\"123456\"},\"supply\":{\"settleType\":\"月结\",\"creditDays\":30,\"accessStatus\":\"淘汰\",\"rejectReason\":\"不合作\"}}")
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(3L, jdbc.queryForObject("SELECT owner_id FROM org", Long.class));
        assertEquals(1L, jdbc.queryForObject("SELECT collaborator_ids[1] FROM org", Long.class));
        assertEquals("2020-03-01", jdbc.queryForObject("SELECT found_date::text FROM org", String.class));
        assertEquals(2, count("org_tag_rel"));
        assertEquals(1, count("org_invoice"));
        assertEquals(30, jdbc.queryForObject("SELECT credit_days FROM org_supply", Integer.class));
    }

    @Test
    void requiredFieldsAndConditionalSupplyValidationDoNotWrite() throws Exception {
        for (String body : new String[]{"{}", "{\"name\":\"A\",\"tags\":[\"分销\"]}",
                "{\"name\":\"有效名称\",\"tags\":[]}", "{\"name\":\"有效名称\",\"tags\":[\"不存在\"]}",
                "{\"name\":\"有效名称\",\"tags\":[\"分销\"],\"creditCode\":\"bad\"}",
                "{\"name\":\"有效名称\",\"tags\":[\"供应\"],\"supply\":{\"settleType\":\"月结\"}}",
                "{\"name\":\"有效名称\",\"tags\":[\"供应\"],\"supply\":{\"accessStatus\":\"淘汰\"}}"}) {
            create(body).andExpect(jsonPath("$.code").value(1001));
        }
        assertEquals(0, count("org"));
        assertEquals(0, count("audit_log"));
        create("{\"name\":\"允许补充资源\",\"tags\":[\"供应\"]}").andExpect(jsonPath("$.code").value(0));
        assertEquals(0, count("org_supply"));
    }

    @Test
    void invoiceDefaultTitleBoundaryAndBlankFields() throws Exception {
        String name = "长".repeat(81);
        create("{\"name\":\"" + name + "\",\"tags\":[\"分销\"],\"invoice\":{}}")
                .andExpect(jsonPath("$.code").value(1001));
        assertEquals(0, count("org"));
        assertEquals(0, count("org_invoice"));
        assertEquals(0, count("audit_log"));
        create("{\"name\":\"" + name + "\",\"tags\":[\"分销\"],\"invoice\":{\"title\":\"有效抬头\",\"taxNo\":\"   \",\"account\":\"   \"}}")
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("有效抬头", jdbc.queryForObject("SELECT title FROM org_invoice", String.class));
        assertNull(jdbc.queryForObject("SELECT tax_no FROM org_invoice", String.class));
        assertNull(jdbc.queryForObject("SELECT account FROM org_invoice", String.class));
    }

    @Test
    void malformedSupplyIsIgnoredOnlyForNonSuppliers() throws Exception {
        String[] malformed = {"{\"settleType\":\"未知\",\"creditDays\":-1}", "\"not an object\"", "{\"creditDays\":{\"bad\":true}}"};
        for (int i = 0; i < malformed.length; i++) {
            create("{\"name\":\"非供应企业" + i + "\",\"tags\":[\"分销\"],\"supply\":" + malformed[i] + "}")
                    .andExpect(jsonPath("$.code").value(0));
            create("{\"name\":\"供应企业验证" + i + "\",\"tags\":[\"供应\"],\"supply\":" + malformed[i] + "}")
                    .andExpect(jsonPath("$.code").value(1001));
        }
        assertEquals(0, count("org_supply"));
        assertEquals(3, count("org"));
        assertEquals(3, count("audit_log"));
    }

    @Test
    void idempotencyReplaysAndRejectsDifferentPayload() throws Exception {
        String body = "{\"name\":\"幂等测试企业\",\"tags\":[\"分销\"],\"settlementContact\":\"幂等联系人\"}";
        String first = create(body, "same-key").andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String second = create(body, "same-key").andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        assertEquals(first, second);
        assertEquals("幂等联系人", jdbc.queryForObject("SELECT settlement_contact FROM org", String.class));
        create("{\"name\":\"另一测试企业\",\"tags\":[\"分销\"]}", "same-key").andExpect(jsonPath("$.code").value(3001));
        assertEquals(1, count("org"));
        assertEquals(1, count("audit_log"));
    }

    @Test
    void duplicateCreditCodeIsRejected() throws Exception {
        create("{\"name\":\"第一测试企业\",\"tags\":[\"分销\"],\"creditCode\":\"91310000123456789X\"}")
                .andExpect(jsonPath("$.code").value(0));
        create("{\"name\":\"第二测试企业\",\"tags\":[\"分销\"],\"creditCode\":\"91310000123456789X\"}")
                .andExpect(jsonPath("$.code").value(3001));
        assertEquals(1, count("org"));
    }

    @Test
    void auditFailureRollsBackEverythingAndKeyCanBeRetried() throws Exception {
        jdbc.execute("ALTER TABLE audit_log ADD CONSTRAINT t_reject_audit CHECK (actor_id < 0)");
        String body = "{\"name\":\"回滚测试企业\",\"tags\":[\"供应\"],\"settlementContact\":\"回滚联系人\",\"supply\":{\"settleContact\":\"供应联系人\"}}";
        create(body, "rollback-key").andExpect(status().isInternalServerError());
        assertEquals(0, count("org"));
        assertEquals(0, count("org_tag_rel"));
        assertEquals(0, count("org_supply"));
        assertEquals(0, count("audit_log"));
        assertEquals(0, count("idempotency_record"));
        jdbc.execute("ALTER TABLE audit_log DROP CONSTRAINT t_reject_audit");
        String retried = create(body, "rollback-key").andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        String replayed = create(body, "rollback-key").andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
        assertEquals(retried, replayed);
        assertEquals("回滚联系人", jdbc.queryForObject("SELECT settlement_contact FROM org", String.class));
        assertEquals("供应联系人", jdbc.queryForObject("SELECT settle_contact FROM org_supply", String.class));
        assertEquals(1, count("org"));
        assertEquals(1, count("org_supply"));
        assertEquals(1, count("audit_log"));
        assertEquals(1, count("idempotency_record"));
    }

    @Test
    void concurrentIdenticalRequestsCreateOnlyOnce() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<String>>();
            for (int i = 0; i < 2; i++) futures.add(executor.submit(() -> {
                login(1, "SALES");
                try {
                    ready.countDown();
                    if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                    return create("{\"name\":\"并发测试企业\",\"tags\":[\"分销\"]}", "concurrent-key")
                            .andExpect(jsonPath("$.code").value(0)).andReturn().getResponse().getContentAsString();
                } finally { AuthContext.clear(); }
            }));
            try {
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
                start.countDown();
                assertEquals(futures.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS), futures.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS));
            } finally { start.countDown(); }
        }
        assertEquals(1, count("org"));
        assertEquals(1, count("audit_log"));
        assertEquals(1, count("idempotency_record"));
    }

    @Test
    void concurrentDifferentKeysWithSameCreditCodeHaveOneWinner() throws Exception {
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var futures = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 2; i++) {
                final int n = i;
                futures.add(executor.submit(() -> {
                    login(1, "SALES");
                    try {
                        ready.countDown();
                        if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new AssertionError("start timeout");
                        String response = create("{\"name\":\"并发企业" + n + "\",\"tags\":[\"分销\"],\"creditCode\":\"91310000123456789X\"}", "credit-key-" + n)
                                .andReturn().getResponse().getContentAsString();
                        return new ObjectMapper().readTree(response).get("code").asInt();
                    } finally { AuthContext.clear(); }
                }));
            }
            try {
                assertTrue(ready.await(10, java.util.concurrent.TimeUnit.SECONDS));
                start.countDown();
                var codes = java.util.List.of(futures.get(0).get(20, java.util.concurrent.TimeUnit.SECONDS), futures.get(1).get(20, java.util.concurrent.TimeUnit.SECONDS));
                assertTrue(codes.contains(0));
                assertTrue(codes.contains(3001));
            } finally { start.countDown(); }
        }
        assertEquals(1, count("org"));
        assertEquals(1, count("audit_log"));
    }

    @Test
    void topLevelSettlementContactIsStoredForSupplierAndNonSupplier() throws Exception {
        create("{\"name\":\"分销联系人企业\",\"tags\":[\"分销\"],\"settlementContact\":\"张三\"}")
                .andExpect(jsonPath("$.code").value(0));
        create("{\"name\":\"供应联系人企业\",\"tags\":[\"供应\"],\"settlementContact\":\"李四\",\"supply\":{\"settleContact\":\"王五\"}}")
                .andExpect(jsonPath("$.code").value(0));
        assertEquals("张三", jdbc.queryForObject("SELECT settlement_contact FROM org WHERE name=?", String.class, "分销联系人企业"));
        assertEquals("李四", jdbc.queryForObject("SELECT settlement_contact FROM org WHERE name=?", String.class, "供应联系人企业"));
        assertEquals("王五", jdbc.queryForObject("SELECT settle_contact FROM org_supply", String.class));
        assertEquals(1, count("org_supply"));
    }

    @Test
    void settlementContactLengthBoundary() throws Exception {
        String max = "张".repeat(20);
        create("{\"name\":\"长度边界企业\",\"tags\":[\"分销\"],\"settlementContact\":\"" + max + "\"}")
                .andExpect(jsonPath("$.code").value(0));
        assertEquals(max, jdbc.queryForObject("SELECT settlement_contact FROM org", String.class));
        create("{\"name\":\"长度超限企业\",\"tags\":[\"供应\"],\"settlementContact\":\"" + max + "张\"}")
                .andExpect(jsonPath("$.code").value(1001));
        assertEquals(1, count("org"));
        assertEquals(1, count("audit_log"));
    }

    @Test
    void missingKeyAndAnonymousAreRejected() throws Exception {
        String body = "{\"name\":\"测试企业\",\"tags\":[\"分销\"]}";
        mvc.perform(post("/api/v1/partners").contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(body))
                .andExpect(jsonPath("$.code").value(1001));
        AuthContext.clear();
        create(body).andExpect(jsonPath("$.code").value(1002));
        assertEquals(0, count("org"));
    }

    private Connection connect() throws SQLException { return user == null ? DriverManager.getConnection(url) : DriverManager.getConnection(url, user, password); }
    private int count(String table) { return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class); }
    private void login(long id, String role) { Instant now = Instant.now(); AuthContext.set(new AuthContext.AuthUser(id, role, "test", now, now.plusSeconds(60), false)); }
    private ResultActions create(String body) throws Exception { return create(body, UUID.randomUUID().toString()); }
    private ResultActions create(String body, String key) throws Exception {
        return mvc.perform(post("/api/v1/partners").header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).characterEncoding("UTF-8").content(body));
    }
}
