package com.crm.api.controller;

import java.sql.Connection;
import java.sql.DriverManager;
import java.time.Instant;

import com.crm.api.common.GlobalExceptionHandler;
import com.crm.api.security.AuthContext;
import com.crm.api.security.RoleInterceptor;
import com.crm.api.service.PartnerDetailService;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Uses connection-local temporary tables; set CRM_TEST_DATABASE_URL to run. */
class PartnerDetailPostgresTest {
    private Connection connection;
    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() throws Exception {
        String url = System.getenv("CRM_TEST_DATABASE_URL");
        assumeTrue(url != null && !url.isBlank(), "Set CRM_TEST_DATABASE_URL to run PostgreSQL tests");
        String user = System.getenv("CRM_TEST_DATABASE_USER");
        connection = user == null ? DriverManager.getConnection(url)
                : DriverManager.getConnection(url, user, System.getenv("CRM_TEST_DATABASE_PASSWORD"));
        jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
        jdbc.execute("CREATE TEMP TABLE sys_user (id bigint PRIMARY KEY, name varchar(30))");
        jdbc.execute("CREATE TEMP TABLE org (id bigint PRIMARY KEY, name varchar(100), short_name varchar(20), credit_code varchar(18), legal_person varchar(30), reg_capital varchar(30), found_date date, industry varchar(30), city varchar(20), address varchar(120), website varchar(80), brief varchar(200), type varchar(10), product_lines jsonb, level varchar(10), status varchar(10), owner_id bigint, collaborator_ids bigint[], created_by bigint, created_at timestamptz, updated_at timestamptz, deleted_at timestamptz)");
        jdbc.execute("CREATE TEMP TABLE org_tag (id bigint PRIMARY KEY, name varchar(10), sort int, enabled boolean)");
        jdbc.execute("CREATE TEMP TABLE org_tag_rel (org_id bigint, tag_id bigint)");
        jdbc.execute("CREATE TEMP TABLE org_invoice (id bigint, org_id bigint, title varchar(80), tax_no varchar(20), bank varchar(60), account varchar(30))");
        jdbc.execute("CREATE TEMP TABLE org_supply (id bigint, org_id bigint, supplier_type text[], cities int, hotels int, room_type varchar(60), peak_guarantee varchar(60), coop_mode text[], coop_start date, settle_type varchar(10), credit_days int, settle_contact varchar(20), access_status varchar(10), reject_reason varchar(100))");
        jdbc.execute("CREATE TEMP TABLE contact (id bigint, org_id bigint, name varchar(20), title varchar(30), phone varchar(20), role_tag varchar(10), is_primary boolean, created_at timestamptz, updated_at timestamptz)");
        jdbc.execute("CREATE TEMP TABLE attachment (id bigint, org_id bigint, file_key varchar(300), file_name varchar(200), mime varchar(100), size bigint, uploaded_by bigint, created_at timestamptz, updated_at timestamptz, deleted_at timestamptz)");
        jdbc.execute("CREATE TEMP TABLE follow_plan (id bigint, org_id bigint, plan_date date, note varchar(60), owner_id bigint, version int, created_at timestamptz, updated_at timestamptz)");
        jdbc.execute("CREATE TEMP TABLE follow (id bigint, org_id bigint, contact_id bigint, deal_id bigint, type varchar(10), content varchar(500), visible_scope varchar(20), created_by bigint, created_at timestamptz, updated_at timestamptz, deleted_at timestamptz, delete_reason varchar(100))");
        jdbc.execute("CREATE TEMP TABLE deal (id bigint, org_id bigint, stage smallint)");
        jdbc.update("INSERT INTO sys_user VALUES (1,'负责人'),(2,'协作人')");
        jdbc.update("INSERT INTO org (id,name,short_name,type,product_lines,level,status,owner_id,collaborator_ids,created_by,created_at,updated_at) VALUES (1,'示例公司','示例','TMC','[\"协议价\"]','重点','合作中',1,ARRAY[2]::bigint[],1,now(),now()),(2,'其他公司','其他','TMC','[]','普通','潜在',2,ARRAY[]::bigint[],2,now(),now()),(3,'已删除','删除','TMC','[]','普通','潜在',1,ARRAY[]::bigint[],1,now(),now())");
        jdbc.update("UPDATE org SET deleted_at=now() WHERE id=3");
        var mapper = Jackson2ObjectMapperBuilder.json().featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build();
        mvc = MockMvcBuilders.standaloneSetup(new PartnerDetailController(new PartnerDetailService(jdbc, mapper)))
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .setControllerAdvice(new GlobalExceptionHandler()).addInterceptors(new RoleInterceptor()).build();
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(1L, "SALES", "test", now, now.plusSeconds(60), false));
    }

    @AfterEach
    void tearDown() throws Exception {
        AuthContext.clear();
        if (connection != null) connection.close();
    }

    @Test
    void anonymousMissingAndDeletedPartnersAreRejected() throws Exception {
        AuthContext.clear();
        mvc.perform(get("/api/v1/partners/1")).andExpect(jsonPath("$.code").value(1002));
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(1L, "SALES", "test", now, now.plusSeconds(60), false));
        mvc.perform(get("/api/v1/partners/999")).andExpect(jsonPath("$.code").value(1001));
        mvc.perform(get("/api/v1/partners/3")).andExpect(jsonPath("$.code").value(1001));
    }

    @Test
    void emptyAssociationsAndSupplyWithoutTag() throws Exception {
        jdbc.update("INSERT INTO org_supply (id,org_id,supplier_type,coop_mode) VALUES (1,1,ARRAY['酒店集团'],ARRAY['协议价'])");
        mvc.perform(get("/api/v1/partners/1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.id").value(1))
                .andExpect(jsonPath("$.data.owner.name").value("负责人"))
                .andExpect(jsonPath("$.data.collaborators[0].name").value("协作人"))
                .andExpect(jsonPath("$.data.supply").value((Object) null))
                .andExpect(jsonPath("$.data.invoice").value((Object) null))
                .andExpect(jsonPath("$.data.plan").value((Object) null))
                .andExpect(jsonPath("$.data.contacts").isEmpty())
                .andExpect(jsonPath("$.data.attachments").isEmpty())
                .andExpect(jsonPath("$.data.recentFollows").isEmpty())
                .andExpect(jsonPath("$.data.metrics.openDeals").value(0))
                .andExpect(jsonPath("$.data.metrics.monthFollows").value(0))
                .andExpect(jsonPath("$.data.metrics.contacts").value(0))
                .andExpect(jsonPath("$.data.metrics.nextPlanDate").value((Object) null));
    }

    @Test
    void invoiceIsMaskedAndSupplyRequiresExtensionAsWellAsTag() throws Exception {
        jdbc.update("INSERT INTO org_tag VALUES (1,'供应',1,true)");
        jdbc.update("INSERT INTO org_tag_rel VALUES (1,1)");
        jdbc.update("INSERT INTO org_invoice VALUES (1,1,'示例公司','TAX','银行','1234567890123456')");
        mvc.perform(get("/api/v1/partners/1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.supply").value((Object) null))
                .andExpect(jsonPath("$.data.invoice.title").value("示例公司"))
                .andExpect(jsonPath("$.data.invoice.accountMasked").value("1234****3456"))
                .andExpect(jsonPath("$.data.invoice.account").doesNotExist());
    }

    @Test
    void adminCanReadAndUnknownRoleIsForbidden() throws Exception {
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(1L, "ADMIN", "test", now, now.plusSeconds(60), false));
        mvc.perform(get("/api/v1/partners/1")).andExpect(jsonPath("$.code").value(0));
        AuthContext.set(new AuthContext.AuthUser(1L, "UNKNOWN", "test", now, now.plusSeconds(60), false));
        mvc.perform(get("/api/v1/partners/1")).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(1003));
    }

    @Test
    void recentFollowsSupportsRequestedLimitAndDeletedPlaceholder() throws Exception {
        jdbc.update("INSERT INTO follow (id,org_id,type,content,created_by,created_at,deleted_at,delete_reason) VALUES (1,1,'电话','最新',1,now(),null,null),(2,1,'电话','秘密',1,now()-interval '1 minute',now(),'误录')");
        mvc.perform(get("/api/v1/partners/1").param("recentFollows", "3"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.recentFollows.length()").value(2))
                .andExpect(jsonPath("$.data.recentFollows[1].deleted").value(true))
                .andExpect(jsonPath("$.data.recentFollows[1].deleteReason").doesNotExist())
                .andExpect(jsonPath("$.data.recentFollows[1].content").value("该记录已被删除"));
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(1L, "ADMIN", "test", now, now.plusSeconds(60), false));
        mvc.perform(get("/api/v1/partners/1"))
                .andExpect(jsonPath("$.data.recentFollows[1].deleteReason").value("误录"));
        for (String invalid : new String[] {"6", "-1", "abc"}) {
            mvc.perform(get("/api/v1/partners/1").param("recentFollows", invalid))
                    .andExpect(jsonPath("$.code").value(1001));
        }
        mvc.perform(get("/api/v1/partners/1").param("recentFollows", "1"))
                .andExpect(jsonPath("$.data.recentFollows.length()").value(1));
    }

    @Test
    void aggregatesOnlyThisPartnersDataAndAppliesMetricsAndSoftDeleteRules() throws Exception {
        jdbc.update("INSERT INTO org_tag VALUES (1,'供应',20,true),(2,'分销',10,true)");
        jdbc.update("INSERT INTO org_tag_rel VALUES (1,1),(1,2)");
        jdbc.update("INSERT INTO org_supply (id,org_id,supplier_type,cities,coop_mode) VALUES (1,1,ARRAY['酒店集团'],10,ARRAY['协议价'])");
        jdbc.update("INSERT INTO contact VALUES (1,1,'张三','经理','13812345678','对接人',true,now(),now()),(2,2,'别人',null,null,null,false,now(),now())");
        jdbc.update("INSERT INTO attachment VALUES (1,1,'key','旧文件.pdf','application/pdf',10,1,now()-interval '1 day',now(),null),(2,1,'key2','新文件.pdf','application/pdf',20,1,now(),now(),null),(3,1,'key3','删除.pdf','application/pdf',10,1,now(),now(),now()),(4,2,'key4','别家.pdf','application/pdf',10,1,now(),now(),null)");
        jdbc.update("INSERT INTO follow_plan (id,org_id,plan_date,note,owner_id,version) VALUES (1,1,DATE '2030-01-02','计划',1,3)");
        jdbc.update("INSERT INTO deal VALUES (1,1,0),(2,1,3),(3,1,4),(4,2,1)");
        jdbc.update("INSERT INTO follow (id,org_id,type,content,created_by,created_at,deleted_at) VALUES (1,1,'电话','本月',1,now(),null),(2,1,'系统','系统',1,now()-interval '1 minute',null),(3,1,'电话','已删除',1,now()-interval '2 minutes',now()),(4,1,'电话','上月',1,date_trunc('month',now())-interval '1 day',null),(5,2,'电话','别家',1,now(),null)");
        mvc.perform(get("/api/v1/partners/1"))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tags[0]").value("分销"))
                .andExpect(jsonPath("$.data.tags[1]").value("供应"))
                .andExpect(jsonPath("$.data.supply.cities").value(10))
                .andExpect(jsonPath("$.data.contacts.length()").value(1))
                .andExpect(jsonPath("$.data.contacts[0].phoneMasked").value("138****5678"))
                .andExpect(jsonPath("$.data.contacts[0].phoneRaw").doesNotExist())
                .andExpect(jsonPath("$.data.attachments.length()").value(2))
                .andExpect(jsonPath("$.data.attachments[0].fileName").value("新文件.pdf"))
                .andExpect(jsonPath("$.data.recentFollows.length()").value(2))
                .andExpect(jsonPath("$.data.recentFollows[0].id").value(1))
                .andExpect(jsonPath("$.data.recentFollows[1].id").value(2))
                .andExpect(jsonPath("$.data.plan.version").value(3))
                .andExpect(jsonPath("$.data.metrics.openDeals").value(2))
                .andExpect(jsonPath("$.data.metrics.monthFollows").value(1))
                .andExpect(jsonPath("$.data.metrics.contacts").value(1))
                .andExpect(jsonPath("$.data.metrics.nextPlanDate").value("2030-01-02"));
    }
}
