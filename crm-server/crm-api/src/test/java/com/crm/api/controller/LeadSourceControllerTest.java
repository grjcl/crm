package com.crm.api.controller;

import java.time.Instant;
import java.util.List;

import com.crm.api.common.GlobalExceptionHandler;
import com.crm.api.dto.LeadSourceDtos;
import com.crm.api.security.AuthContext;
import com.crm.api.security.RoleInterceptor;
import com.crm.api.service.LeadSourceService;
import com.crm.domain.entity.LeadSource;
import com.crm.infra.mapper.LeadMapper;
import com.crm.infra.mapper.LeadSourceMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 来源字典 3 个接口：权限、长度/重名校验、sort=max+10、审计与删除引用校验。
 *
 * <p>用真实的 {@link LeadSourceService} + mock 的 Mapper/audit JdbcTemplate 走 MockMvc，
 * 避免依赖外部 PostgreSQL/Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
class LeadSourceControllerTest {

    @Mock
    private LeadSourceMapper leadSourceMapper;
    @Mock
    private LeadMapper leadMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        LeadSourceService service = new LeadSourceService(
                leadSourceMapper, leadMapper, jdbcTemplate, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new LeadSourceController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new RoleInterceptor())
                .build();
    }

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void createReturnsNextSortAndWritesCreateAudit() throws Exception {
        loginAs("ADMIN");
        when(leadSourceMapper.selectCount(any())).thenReturn(0L);
        when(leadSourceMapper.selectList(any())).thenReturn(List.of(source(7L, "招标", 70)));
        when(leadSourceMapper.insert(any(LeadSource.class))).thenAnswer(invocation -> {
            LeadSource inserted = invocation.getArgument(0);
            inserted.setId(8L);
            return 1;
        });

        post("{\"name\":\" 测试来源 \"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.id").value(8))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.name").value("测试来源"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.sort").value(80));

        ArgumentCaptor<LeadSource> inserted = ArgumentCaptor.forClass(LeadSource.class);
        verify(leadSourceMapper).insert(inserted.capture());
        assertEquals("测试来源", inserted.getValue().getName());
        assertEquals(80, inserted.getValue().getSort());

        Object[] audit = captureAudit();
        assertEquals(1L, audit[0]);
        assertEquals("CREATE", audit[1]);
        assertEquals("lead_source", audit[2]);
        assertEquals(8L, audit[3]);
        assertTrue(String.valueOf(audit[4]).contains("\"name\":\"测试来源\""));
    }

    @Test
    void createStartsSortAtTenWhenTableEmpty() throws Exception {
        loginAs("ADMIN");
        when(leadSourceMapper.selectCount(any())).thenReturn(0L);
        when(leadSourceMapper.selectList(any())).thenReturn(List.of());
        when(leadSourceMapper.insert(any(LeadSource.class))).thenAnswer(invocation -> {
            ((LeadSource) invocation.getArgument(0)).setId(1L);
            return 1;
        });

        post("{\"name\":\"新来源\"}")
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.sort").value(10));
    }

    @Test
    void duplicateNameReturns3001() throws Exception {
        loginAs("ADMIN");
        when(leadSourceMapper.selectCount(any())).thenReturn(1L);

        post("{\"name\":\"测试来源\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(3001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("来源名称已存在"));

        verify(leadSourceMapper, never()).insert(any(LeadSource.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void blankNameReturns1001() throws Exception {
        loginAs("ADMIN");

        post("{\"name\":\"   \"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("请输入来源名称"));

        verifyNoInteractions(leadSourceMapper, leadMapper, jdbcTemplate);
    }

    @Test
    void tooLongNameReturns1001() throws Exception {
        loginAs("ADMIN");

        // lead_source.name 为 varchar(10)（shared/db），因此 11 字即超长。
        post("{\"name\":\"一二三四五六七八九十一\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("来源名称不能超过 10 字"));

        verifyNoInteractions(leadSourceMapper, leadMapper, jdbcTemplate);
    }

    @Test
    void renameWritesOldAndNewNameInAudit() throws Exception {
        loginAs("ADMIN");
        when(leadSourceMapper.selectList(any())).thenReturn(List.of(source(1L, "行业展会", 20)));
        when(leadSourceMapper.selectCount(any())).thenReturn(0L);
        when(leadSourceMapper.updateById(any(LeadSource.class))).thenReturn(1);

        put(1L, "{\"name\":\"改名\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.id").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.name").value("改名"));

        ArgumentCaptor<LeadSource> patch = ArgumentCaptor.forClass(LeadSource.class);
        verify(leadSourceMapper).updateById(patch.capture());
        assertEquals(1L, patch.getValue().getId());
        assertEquals("改名", patch.getValue().getName());

        Object[] audit = captureAudit();
        assertEquals("UPDATE", audit[1]);
        assertEquals(1L, audit[3]);
        String diff = String.valueOf(audit[4]);
        assertTrue(diff.contains("\"oldName\":\"行业展会\""));
        assertTrue(diff.contains("\"newName\":\"改名\""));
    }

    @Test
    void renameMissingSourceReturns1001() throws Exception {
        loginAs("ADMIN");
        when(leadSourceMapper.selectList(any())).thenReturn(List.of());

        put(999L, "{\"name\":\"改名\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("来源不存在"));

        verify(leadSourceMapper, never()).updateById(any(LeadSource.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void renameToExistingNameReturns3001() throws Exception {
        loginAs("ADMIN");
        when(leadSourceMapper.selectList(any())).thenReturn(List.of(source(1L, "行业展会", 20)));
        when(leadSourceMapper.selectCount(any())).thenReturn(1L);

        put(1L, "{\"name\":\"展会\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(3001));

        verify(leadSourceMapper, never()).updateById(any(LeadSource.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void deleteBlockedByReferencingLeadsReturns3002WithCount() throws Exception {
        loginAs("ADMIN");
        when(leadSourceMapper.selectList(any())).thenReturn(List.of(source(2L, "行业展会", 20)));
        when(leadMapper.selectCount(any())).thenReturn(3L);

        delete(2L)
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(3002))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message")
                        .value("该来源下还有 3 条发现记录，请先处理"));

        verify(leadSourceMapper, never()).deleteById(any(java.io.Serializable.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void deleteLastSourceIsRejected() throws Exception {
        loginAs("ADMIN");
        when(leadSourceMapper.selectList(any())).thenReturn(List.of(source(2L, "转介绍", 10)));
        when(leadMapper.selectCount(any())).thenReturn(0L);
        when(leadSourceMapper.selectCount(any())).thenReturn(1L);

        delete(2L)
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(3002))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("至少保留一个来源"));

        verify(leadSourceMapper, never()).deleteById(any(java.io.Serializable.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void deleteWithoutReferencesRemovesRowAndWritesAudit() throws Exception {
        loginAs("ADMIN");
        when(leadSourceMapper.selectList(any())).thenReturn(List.of(source(3L, "测试来源", 80)));
        when(leadMapper.selectCount(any())).thenReturn(0L);
        when(leadSourceMapper.selectCount(any())).thenReturn(7L);
        when(leadSourceMapper.deleteById(any(java.io.Serializable.class))).thenReturn(1);

        delete(3L)
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data").value((Object) null));

        verify(leadSourceMapper).deleteById(3L);
        Object[] audit = captureAudit();
        assertEquals("DELETE", audit[1]);
        assertEquals(3L, audit[3]);
        assertTrue(String.valueOf(audit[4]).contains("\"name\":\"测试来源\""));
    }

    @Test
    void deleteMissingSourceReturns1001() throws Exception {
        loginAs("ADMIN");
        when(leadSourceMapper.selectList(any())).thenReturn(List.of());

        delete(999L)
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("来源不存在"));

        verifyNoInteractions(leadMapper, jdbcTemplate);
    }

    @Test
    void salesCannotCreateRenameOrDelete() throws Exception {
        loginAs("SALES");

        post("{\"name\":\"测试来源\"}")
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1003));
        put(1L, "{\"name\":\"改名\"}")
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1003));
        delete(1L)
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1003));

        verifyNoInteractions(leadSourceMapper, leadMapper, jdbcTemplate);
    }

    @Test
    void unauthenticatedRequestReturns1002() throws Exception {
        post("{\"name\":\"测试来源\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1002));

        verifyNoInteractions(leadSourceMapper, leadMapper, jdbcTemplate);
    }

    private ResultActions post(String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/lead-sources")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions put(Long id, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/lead-sources/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions delete(Long id) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/lead-sources/{id}", id));
    }

    private LeadSource source(Long id, String name, Integer sort) {
        LeadSource source = new LeadSource();
        source.setId(id);
        source.setName(name);
        source.setSort(sort);
        return source;
    }

    private Object[] captureAudit() {
        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(jdbcTemplate).update(anyString(), captor.capture());
        return captor.getValue();
    }

    private void loginAs(String role) {
        Instant now = Instant.now();
        AuthContext.set(new AuthContext.AuthUser(
                1L, role, "jti", now, now.plusSeconds(60), false));
    }
}
