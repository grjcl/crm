package com.crm.api.controller;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;

import com.crm.api.common.GlobalExceptionHandler;
import com.crm.api.security.AuthContext;
import com.crm.api.security.RoleInterceptor;
import com.crm.api.service.PartnerDedupService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real pg_trgm integration tests. Set CRM_TEST_DATABASE_URL to a JDBC PostgreSQL URL.
 * Uses connection-local temporary tables only; no shared data is modified. pg_trgm must already exist.
 */
class PartnerDedupPostgresTest {
    private Connection connection;
    private SingleConnectionDataSource dataSource;
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("CRM_TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "Set CRM_TEST_DATABASE_URL to run PostgreSQL integration tests");
        String user = System.getenv("CRM_TEST_DATABASE_USER");
        String password = System.getenv("CRM_TEST_DATABASE_PASSWORD");
        connection = user == null ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, password);
        dataSource = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TEMP TABLE sys_user (id bigint PRIMARY KEY, name varchar(30))");
        jdbc.execute("CREATE TEMP TABLE org (id bigint PRIMARY KEY, name varchar(100), credit_code varchar(18), owner_id bigint, level varchar(20), deleted_at timestamptz)");
        jdbc.update("INSERT INTO sys_user VALUES (1, '王磊')");
        mvc = MockMvcBuilders.standaloneSetup(new PartnerDedupController(new PartnerDedupService(jdbc)))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new RoleInterceptor()).build();
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(1L, "SALES", "jti", now, now.plusSeconds(60), false));
    }

    @AfterEach
    void tearDown() throws Exception {
        AuthContext.clear();
        if (connection != null) connection.close();
    }

    @Test
    void creditCodeTakesPriorityOverNameAndReturnsOwnerAndLevel() throws Exception {
        org(1, "完全不同的企业", "ABC123", false);
        org(2, "中航商旅服务有限公司", null, false);
        request("{\"name\":\"中航商旅服务有限公司\",\"creditCode\":\"ABC123\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hit").value(true))
                .andExpect(jsonPath("$.data.type").value("CREDIT_CODE"))
                .andExpect(jsonPath("$.data.matches.length()").value(1))
                .andExpect(jsonPath("$.data.matches[0].id").value(1))
                .andExpect(jsonPath("$.data.matches[0].name").value("完全不同的企业"))
                .andExpect(jsonPath("$.data.matches[0].owner").value("王磊"))
                .andExpect(jsonPath("$.data.matches[0].level").value("重点"))
                .andExpect(jsonPath("$.data.matches[0].similarity").value(1.0));
    }

    @Test
    void nameNormalizationRemovesParenthesesWhitespaceAndNormalizesWidthAndCase() throws Exception {
        org(3, "中航商旅服务（上海）有限公司 ＡＢＣ", null, false);
        request("{\"name\":\"中航商旅服务(北京)有限公司 abc\"}")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hit").value(true))
                .andExpect(jsonPath("$.data.type").value("NAME_LIKE"))
                .andExpect(jsonPath("$.data.matches[0].id").value(3))
                .andExpect(jsonPath("$.data.matches[0].similarity").value(1.0));
    }

    @Test
    void nestedParenthesizedGroupsAreRemovedOnBothSides() throws Exception {
        org(3, "中航商旅（上海（浦东）分公司）服务有限公司", null, false);
        request("{\"name\":\"中航商旅(北京(朝阳)分公司)服务有限公司\"}")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.type").value("NAME_LIKE"))
                .andExpect(jsonPath("$.data.matches[0].id").value(3))
                .andExpect(jsonPath("$.data.matches[0].similarity").value(1.0));
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        AuthContext.clear();
        request("{\"name\":\"中航商旅服务有限公司\"}")
                .andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void noMatchReturnsEmptyResult() throws Exception {
        org(4, "完全不同企业", null, false);
        request("{\"name\":\"中航商旅服务有限公司\"}")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hit").value(false))
                .andExpect(jsonPath("$.data.type").value((Object) null))
                .andExpect(jsonPath("$.data.matches").isEmpty());
    }

    @Test
    void blankAndMissingNamesReturnValidationError() throws Exception {
        request("{\"name\":\"   \"}").andExpect(jsonPath("$.code").value(1001));
        request("{}").andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void deletedRowsNeverMatchEitherCreditCodeOrName() throws Exception {
        org(5, "中航商旅服务有限公司", "DELETED", true);
        request("{\"name\":\"中航商旅服务有限公司\",\"creditCode\":\"DELETED\"}")
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hit").value(false))
                .andExpect(jsonPath("$.data.type").value((Object) null))
                .andExpect(jsonPath("$.data.matches").isEmpty());
    }

    @Test
    void nameMatchesAreLimitedToFive() throws Exception {
        for (int i = 1; i <= 7; i++) org(i, "中航商旅服务有限公司", null, false);
        request("{\"name\":\"中航商旅服务有限公司\"}")
                .andExpect(jsonPath("$.data.type").value("NAME_LIKE"))
                .andExpect(jsonPath("$.data.matches.length()").value(5));
    }

    private void org(long id, String name, String creditCode, boolean deleted) {
        jdbc.update("INSERT INTO org (id,name,credit_code,owner_id,level,deleted_at) VALUES (?,?,?,1,'重点',CASE WHEN ? THEN now() ELSE NULL END)", id, name, creditCode, deleted);
    }

    private ResultActions request(String body) throws Exception {
        return mvc.perform(post("/api/v1/partners/check-dup").contentType(MediaType.APPLICATION_JSON).content(body));
    }
}
