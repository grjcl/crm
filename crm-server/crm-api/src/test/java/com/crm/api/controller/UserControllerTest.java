package com.crm.api.controller;

import java.time.Instant;
import java.util.List;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.crm.api.common.GlobalExceptionHandler;
import com.crm.api.dto.UserBrief;
import com.crm.api.security.AuthContext;
import com.crm.api.service.UserQueryService;
import com.crm.domain.entity.SysUser;
import com.crm.infra.mapper.SysUserMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserControllerTest {

    private UserQueryService queryService;
    private MockMvc mockMvc;

    @BeforeAll
    static void initLambdaCache() {
        // 单测没有 Spring/MyBatis 启动流程，需手动注册实体的字段缓存
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), SysUser.class);
    }

    @BeforeEach
    void setUp() {
        queryService = mock(UserQueryService.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new UserController(queryService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @AfterEach
    void clearContext() {
        AuthContext.clear();
    }

    @Test
    void defaultQueryForcesActiveOnly() throws Exception {
        AuthContext.set(user("ADMIN"));
        when(queryService.list(false, null)).thenReturn(List.of(brief()));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/users"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("ok"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.length()").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].id").value(2))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].name").value("王销售"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].dept").value("销售一部"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].employeeNo").value("1002"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].role").value("SALES"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].phoneMasked").value("139****0002"));

        verify(queryService).list(false, null);
    }

    @Test
    void salesCannotSeeDisabledEvenWithEnabledFalse() throws Exception {
        AuthContext.set(user("SALES"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/users").param("enabled", "false"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0));

        verify(queryService).list(false, null);
    }

    @Test
    void adminCanSeeDisabledWithEnabledFalse() throws Exception {
        AuthContext.set(user("ADMIN"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/users").param("enabled", "false"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0));

        verify(queryService).list(true, null);
    }

    @Test
    void enabledTrueIsActiveOnlyForAdmin() throws Exception {
        AuthContext.set(user("ADMIN"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/users").param("enabled", "true"))
                .andExpect(MockMvcResultMatchers.status().isOk());

        verify(queryService).list(false, null);
    }

    @Test
    void qIsPassedThrough() throws Exception {
        AuthContext.set(user("SALES"));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/users").param("q", "销售"))
                .andExpect(MockMvcResultMatchers.status().isOk());

        verify(queryService).list(false, "销售");
    }

    @Test
    void missingLoginReturns1002() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/users"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1002));

        verify(queryService, never()).list(anyBoolean(), isNull());
    }

    @Test
    void responseHasNoPasswordOrPlainPhone() throws Exception {
        AuthContext.set(user("ADMIN"));
        when(queryService.list(false, null)).thenReturn(List.of(brief()));

        String body = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/users"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertTrue(body.contains("139****0002"));
        assertFalse(body.contains("password"), "出参不应包含 password 字段");
        assertFalse(body.contains("13900000002"), "出参不应包含明文手机号");

        List<String> components = java.util.Arrays.stream(UserBrief.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName)
                .toList();
        assertFalse(components.contains("passwordHash"));
        assertFalse(components.contains("phone"));
    }

    @Test
    void serviceDefaultsToActiveAndNameAscendingWithoutPasswordColumn() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of(entity("13900000002", "hash-value")));
        UserQueryService service = new UserQueryService(mapper);

        List<UserBrief> users = service.list(false, "  ");

        assertEquals(1, users.size());
        assertEquals("139****0002", users.get(0).phoneMasked());

        LambdaQueryWrapper<SysUser> wrapper = capture(mapper);
        String segment = wrapper.getSqlSegment();
        assertTrue(segment.contains("status ="), segment);
        assertTrue(segment.contains("ORDER BY name ASC"), segment);
        assertFalse(segment.contains("password_hash"), segment);
        assertEquals("id,name,dept,employee_no,role,phone", wrapper.getSqlSelect());
        assertTrue(wrapper.getParamNameValuePairs().containsValue("ACTIVE"));
    }

    @Test
    void serviceIncludeDisabledDropsStatusFilter() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        UserQueryService service = new UserQueryService(mapper);

        assertTrue(service.list(true, null).isEmpty());

        LambdaQueryWrapper<SysUser> wrapper = capture(mapper);
        assertFalse(wrapper.getSqlSegment().contains("status ="), wrapper.getSqlSegment());
        assertFalse(wrapper.getParamNameValuePairs().containsValue("ACTIVE"));
    }

    @Test
    void serviceMatchesKeywordOnNameEmployeeNoOrPhoneCaseInsensitively() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        UserQueryService service = new UserQueryService(mapper);

        service.list(false, " 1002 ");

        LambdaQueryWrapper<SysUser> wrapper = capture(mapper);
        String segment = wrapper.getSqlSegment();
        assertTrue(segment.contains("ILIKE"), segment);
        assertTrue(segment.contains("name"), segment);
        assertTrue(segment.contains("employee_no"), segment);
        assertTrue(segment.contains("phone"), segment);
        assertTrue(wrapper.getParamNameValuePairs().containsValue("%1002%"),
                wrapper.getParamNameValuePairs().toString());
    }

    @Test
    void serviceEscapesWildcardsInKeyword() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        UserQueryService service = new UserQueryService(mapper);

        service.list(false, "100%_a");

        LambdaQueryWrapper<SysUser> wrapper = capture(mapper);
        // getSqlSegment() 触发 MyBatis-Plus 的参数占位替换，参数表在此时才填充
        assertTrue(wrapper.getSqlSegment().contains("ILIKE"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("%100\\%\\_a%"),
                wrapper.getParamNameValuePairs().toString());
    }

    @Test
    void serviceTruncatesKeywordTo40Chars() {
        SysUserMapper mapper = mock(SysUserMapper.class);
        when(mapper.selectList(any())).thenReturn(List.of());
        UserQueryService service = new UserQueryService(mapper);

        service.list(false, "a".repeat(50));

        LambdaQueryWrapper<SysUser> wrapper = capture(mapper);
        assertTrue(wrapper.getSqlSegment().contains("ILIKE"));
        assertTrue(wrapper.getParamNameValuePairs().containsValue("%" + "a".repeat(40) + "%"));
    }

    @SuppressWarnings("unchecked")
    private LambdaQueryWrapper<SysUser> capture(SysUserMapper mapper) {
        ArgumentCaptor<Wrapper<SysUser>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(mapper).selectList(captor.capture());
        return (LambdaQueryWrapper<SysUser>) captor.getValue();
    }

    private SysUser entity(String phone, String passwordHash) {
        SysUser user = new SysUser();
        user.setId(2L);
        user.setName("王销售");
        user.setPhone(phone);
        user.setEmployeeNo("1002");
        user.setDept("销售一部");
        user.setRole("SALES");
        user.setStatus("ACTIVE");
        user.setPasswordHash(passwordHash);
        return user;
    }

    private UserBrief brief() {
        return new UserBrief(2L, "王销售", "销售一部", "1002", "SALES", "139****0002");
    }

    private AuthContext.AuthUser user(String role) {
        Instant now = Instant.now();
        return new AuthContext.AuthUser(1L, role, "jti", now, now.plusSeconds(60), false);
    }
}
