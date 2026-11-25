package com.crm.api.controller;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;

import com.crm.api.common.GlobalExceptionHandler;
import com.crm.api.security.AuthContext;
import com.crm.api.security.RoleInterceptor;
import com.crm.api.service.PartnerListService;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** PostgreSQL integration tests using only connection-local temporary tables and view.
 * Set CRM_TEST_DATABASE_URL, CRM_TEST_DATABASE_USER and CRM_TEST_DATABASE_PASSWORD to run.
 */
class PartnerListPostgresTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private JdbcTemplate observed;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("CRM_TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "Set CRM_TEST_DATABASE_URL to run PostgreSQL integration tests");
        String user = System.getenv("CRM_TEST_DATABASE_USER");
        connection = user == null ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, System.getenv("CRM_TEST_DATABASE_PASSWORD"));
        var ds = new SingleConnectionDataSource(connection, true);
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TEMP TABLE sys_user (id bigint PRIMARY KEY, name varchar(30))");
        jdbc.execute("CREATE TEMP TABLE org (id bigint PRIMARY KEY, name varchar(100), short_name varchar(20), level varchar(10), status varchar(10), city varchar(20), type varchar(10), industry varchar(30), owner_id bigint, deleted_at timestamptz)");
        jdbc.execute("CREATE TEMP TABLE org_tag (id bigint PRIMARY KEY, name varchar(10), sort int, enabled boolean)");
        jdbc.execute("CREATE TEMP TABLE org_tag_rel (org_id bigint, tag_id bigint)");
        jdbc.execute("CREATE TEMP TABLE follow_plan (id bigint PRIMARY KEY, org_id bigint, plan_date date, note varchar(60), owner_id bigint)");
        jdbc.execute("CREATE TEMP TABLE follow (id bigint PRIMARY KEY, org_id bigint, created_at timestamptz, deleted_at timestamptz)");
        jdbc.execute("CREATE TEMP VIEW v_partner_list AS SELECT o.id,o.name,o.short_name,o.level,o.status,o.city,o.owner_id,p.plan_date,p.note AS plan_note FROM org o LEFT JOIN follow_plan p ON p.org_id=o.id WHERE o.deleted_at IS NULL");
        jdbc.update("INSERT INTO sys_user VALUES (1,'销售一'),(2,'销售二')");
        jdbc.update("INSERT INTO org_tag VALUES (1,'分销',10,true),(2,'供应',20,true)");
        observed = spy(new JdbcTemplate(ds));
        var mapper = Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        var service = new PartnerListService(observed);
        service.detectCollation();
        clearInvocations(observed);
        mvc = MockMvcBuilders.standaloneSetup(new PartnerListController(service))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new RoleInterceptor()).build();
        login();
    }

    @AfterEach
    void tearDown() throws Exception {
        AuthContext.clear();
        if (connection != null) connection.close();
    }

    @Test
    void anonymousRequestIsRejected() throws Exception {
        AuthContext.clear();
        request().andExpect(jsonPath("$.code").value(1002));
    }

    @Test
    void sortsOverdueFirstAndNoPlanLastExcludingDeleted() throws Exception {
        org(1,"Beta", "bee",false); org(2,"Alpha", "aye",false); org(3,"Zeta", "zee",false); org(4,"Deleted", "gone",true);
        jdbc.update("INSERT INTO follow_plan VALUES (1,1,(now() at time zone 'Asia/Shanghai')::date-2,'逾期',1),(2,2,(now() at time zone 'Asia/Shanghai')::date-2,'逾期',1),(3,4,current_date-20,'删除',1)");
        request().andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.list[0].id").value(2)).andExpect(jsonPath("$.data.list[1].id").value(1))
                .andExpect(jsonPath("$.data.list[2].id").value(3)).andExpect(jsonPath("$.data.list[0].plan.overdueDays").value(2))
                .andExpect(jsonPath("$.data.list[0].plan.daysFromToday").value(-2)).andExpect(jsonPath("$.data.list[2].plan").value((Object)null));
    }

    @Test
    void tagsKeywordAndPaginationComposeWithCorrectTotal() throws Exception {
        org(1,"Alpha travel", "one",false); org(2,"Beta travel", "ALPHA",false); org(3,"Alpha hidden", "three",false); org(4,"Alpha deleted", "four",true);
        jdbc.update("INSERT INTO org_tag_rel VALUES (1,1),(2,1),(3,2),(4,1)");
        mvc.perform(get("/api/v1/partners").param("tag","分销").param("q"," alpha ").param("page","2").param("size","1"))
                .andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.page").value(2)).andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.list.length()").value(1)).andExpect(jsonPath("$.data.list[0].id").value(2))
                .andExpect(jsonPath("$.data.list[0].tags[0]").value("分销"));
        mvc.perform(get("/api/v1/partners").param("tag","全部").param("q","   "))
                .andExpect(jsonPath("$.data.total").value(3));
    }

    @Test
    void emptyAndOutOfRangePagesPreserveTotal() throws Exception {
        request().andExpect(jsonPath("$.code").value(0)).andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.list").isEmpty());
        org(1,"Alpha", "a",false);
        mvc.perform(get("/api/v1/partners").param("page","2").param("size","1"))
                .andExpect(jsonPath("$.data.total").value(1)).andExpect(jsonPath("$.data.list").isEmpty());
    }

    @Test
    void rejectsInvalidPaginationAndEnumAndReturnsLatestUndeletedFollow() throws Exception {
        mvc.perform(get("/api/v1/partners").param("size","101")).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(get("/api/v1/partners").param("level","不存在")).andExpect(jsonPath("$.code").value(1001));
        org(1,"Alpha", "a",false);
        jdbc.update("INSERT INTO follow VALUES (1,1,'2026-01-01 12:00:00+08',null),(2,1,'2026-01-02 12:00:00+08',now())");
        request().andExpect(jsonPath("$.data.list[0].lastFollowAt").value("2026-01-01"));
    }

    @Test
    void tagQueriesDoNotGrowWithPageSize() throws Exception {
        for (int i = 1; i <= 12; i++) {
            org(i,"Company" + i, "s" + i,false);
            jdbc.update("INSERT INTO org_tag_rel VALUES (?,1)", i);
        }
        mvc.perform(get("/api/v1/partners").param("size","1")).andExpect(jsonPath("$.data.total").value(12));
        long one = tagQueryCount();
        clearInvocations(observed);
        mvc.perform(get("/api/v1/partners").param("size","12")).andExpect(jsonPath("$.data.list.length()").value(12));
        long many = tagQueryCount();
        assertTrue(one > 0, "the tag query must be observed");
        assertEquals(one, many, "tag queries must be batched, not one query per partner");
    }

    private long tagQueryCount() {
        return mockingDetails(observed).getInvocations().stream()
                .filter(i -> i.getMethod().getName().startsWith("query"))
                .filter(i -> i.getArguments().length > 0 && i.getArgument(0) instanceof String)
                .filter(i -> ((String)i.getArgument(0)).toLowerCase().contains("org_tag"))
                .count();
    }

    private void org(long id, String name, String shortName, boolean deleted) {
        jdbc.update("INSERT INTO org VALUES (?,?,?,'重点','合作中','上海','TMC','商旅',1,CASE WHEN ? THEN now() ELSE NULL END)", id,name,shortName,deleted);
    }
    private ResultActions request() throws Exception { return mvc.perform(get("/api/v1/partners")); }
    private void login() {
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(1L,"SALES","test",now,now.plusSeconds(60),false));
    }
}
