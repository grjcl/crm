package com.crm.api.controller;

import java.time.Instant;
import java.util.List;

import com.crm.api.common.GlobalExceptionHandler;
import com.crm.api.dto.TagDtos;
import com.crm.api.security.AuthContext;
import com.crm.api.security.RoleInterceptor;
import com.crm.api.service.TagService;
import com.crm.domain.entity.OrgTag;
import com.crm.infra.mapper.OrgTagMapper;
import com.crm.infra.mapper.OrgTagRelMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 标签字典 4 个接口：列表登录可用且单查询统计、ADMIN 权限、名称校验、重名、
 * sort=max+10、审计 diff 与删除引用校验。
 *
 * <p>用真实 {@link TagService} + mock 的 Mapper/JdbcTemplate 走 MockMvc，
 * 不依赖外部 PostgreSQL/Redis。</p>
 */
@ExtendWith(MockitoExtension.class)
class TagControllerTest {

    @Mock
    private OrgTagMapper tagMapper;
    @Mock
    private OrgTagRelMapper tagRelMapper;
    @Mock
    private JdbcTemplate jdbcTemplate;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TagService service = new TagService(tagMapper, tagRelMapper, jdbcTemplate, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new TagController(service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addInterceptors(new RoleInterceptor())
                .build();
    }

    @AfterEach
    void clearAuthContext() {
        AuthContext.clear();
    }

    @Test
    void listIsAvailableToAnyLoggedInUserWithSingleQuery() throws Exception {
        loginAs("SALES");
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<TagDtos.TagItem>>any()))
                .thenReturn(List.of(item(1L, "分销", 10, true, 2L)));

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/tags"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].id").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].name").value("分销"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].sort").value(10))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].enabled").value(true))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data[0].orgCount").value(2));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, times(1)).query(sql.capture(), ArgumentMatchers.<RowMapper<TagDtos.TagItem>>any());
        String normalized = sql.getValue().replaceAll("\\s+", " ");
        assertTrue(normalized.contains("left join org_tag_rel r on r.tag_id = t.id"));
        assertTrue(normalized.contains("left join org o on o.id = r.org_id and o.deleted_at is null"));
        assertTrue(normalized.contains("count(o.id) as org_count"));
        assertTrue(normalized.contains("group by t.id"));
        assertTrue(normalized.contains("order by t.sort asc, t.id asc"));
    }

    @Test
    void emptyListReturnsEmptyArray() throws Exception {
        loginAs("ADMIN");
        when(jdbcTemplate.query(anyString(), ArgumentMatchers.<RowMapper<TagDtos.TagItem>>any()))
                .thenReturn(List.of());

        mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/tags"))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data").isArray())
                .andExpect(MockMvcResultMatchers.jsonPath("$.data").isEmpty());
    }

    @Test
    void createReturnsNextSortAndWritesCreateAudit() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectCount(any())).thenReturn(0L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(40);
        when(tagMapper.insert(any(OrgTag.class))).thenAnswer(invocation -> {
            OrgTag inserted = invocation.getArgument(0);
            inserted.setId(4L);
            return 1;
        });

        post("{\"name\":\" 测试标签 \"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.id").value(4))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.name").value("测试标签"))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.sort").value(40));

        ArgumentCaptor<OrgTag> inserted = ArgumentCaptor.forClass(OrgTag.class);
        verify(tagMapper).insert(inserted.capture());
        assertEquals("测试标签", inserted.getValue().getName());
        assertEquals(40, inserted.getValue().getSort());
        assertEquals(true, inserted.getValue().getEnabled());

        Object[] audit = captureAudit();
        assertEquals(1L, audit[0]);
        assertEquals("CREATE", audit[1]);
        assertEquals("org_tag", audit[2]);
        assertEquals(4L, audit[3]);
        assertTrue(String.valueOf(audit[4]).contains("\"name\":\"测试标签\""));
    }

    @Test
    void createStartsSortAtTenWhenTableEmpty() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectCount(any())).thenReturn(0L);
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class))).thenReturn(10);
        when(tagMapper.insert(any(OrgTag.class))).thenAnswer(invocation -> {
            ((OrgTag) invocation.getArgument(0)).setId(1L);
            return 1;
        });

        post("{\"name\":\"新标签\"}")
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.sort").value(10));
    }

    @Test
    void blankNameReturns1001() throws Exception {
        loginAs("ADMIN");

        post("{\"name\":\"   \"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("请输入标签名称"));

        verifyNoInteractions(tagMapper, tagRelMapper, jdbcTemplate);
    }

    @Test
    void tooLongNameReturns1001() throws Exception {
        loginAs("ADMIN");

        // org_tag.name 为 varchar(10)，11 字即超长。
        post("{\"name\":\"一二三四五六七八九十一\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("标签名称不能超过 10 字"));

        verifyNoInteractions(tagMapper, tagRelMapper, jdbcTemplate);
    }

    @Test
    void duplicateNameReturns3001() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectCount(any())).thenReturn(1L);

        post("{\"name\":\"测试标签\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(3001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("标签名称已存在"));

        verify(tagMapper, never()).insert(any(OrgTag.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void renameWritesOldAndNewNameInAudit() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectList(any())).thenReturn(List.of(tag(1L, "分销")));
        when(tagMapper.selectCount(any())).thenReturn(0L);
        when(tagMapper.updateById(any(OrgTag.class))).thenReturn(1);

        put(1L, "{\"name\":\"渠道\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.id").value(1))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.name").value("渠道"));

        ArgumentCaptor<OrgTag> patch = ArgumentCaptor.forClass(OrgTag.class);
        verify(tagMapper).updateById(patch.capture());
        assertEquals(1L, patch.getValue().getId());
        assertEquals("渠道", patch.getValue().getName());

        Object[] audit = captureAudit();
        assertEquals("UPDATE", audit[1]);
        assertEquals("org_tag", audit[2]);
        assertEquals(1L, audit[3]);
        String diff = String.valueOf(audit[4]);
        assertTrue(diff.contains("\"oldName\":\"分销\""));
        assertTrue(diff.contains("\"newName\":\"渠道\""));
    }

    @Test
    void renameMissingTagReturns404With1001() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectList(any())).thenReturn(List.of());

        put(999L, "{\"name\":\"改名\"}")
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("标签不存在"));

        verify(tagMapper, never()).updateById(any(OrgTag.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void renameToExistingNameReturns3001() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectList(any())).thenReturn(List.of(tag(1L, "分销")));
        when(tagMapper.selectCount(any())).thenReturn(1L);

        put(1L, "{\"name\":\"供应\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(3001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("标签名称已存在"));

        verify(tagMapper, never()).updateById(any(OrgTag.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void renameToSameNameSkipsUpdateAndAudit() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectList(any())).thenReturn(List.of(tag(1L, "分销")));

        put(1L, "{\"name\":\"分销\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data.name").value("分销"));

        verify(tagMapper, never()).updateById(any(OrgTag.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void deleteBlockedByAnyRelationReturns3002WithCount() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectList(any())).thenReturn(List.of(tag(2L, "供应")));
        when(tagRelMapper.selectCount(any())).thenReturn(1L);

        delete(2L)
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(3002))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message")
                        .value("该标签下还有 1 家合作商，请先移除"));

        verify(tagMapper, never()).deleteById(any(java.io.Serializable.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void deleteLastTagIsRejected() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectList(any())).thenReturn(List.of(tag(3L, "其他")));
        when(tagRelMapper.selectCount(any())).thenReturn(0L);
        when(tagMapper.selectCount(any())).thenReturn(1L);

        delete(3L)
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("至少保留一个标签"));

        verify(tagMapper, never()).deleteById(any(java.io.Serializable.class));
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    void deleteWithoutRelationsRemovesRowAndWritesAudit() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectList(any())).thenReturn(List.of(tag(3L, "其他")));
        when(tagRelMapper.selectCount(any())).thenReturn(0L);
        when(tagMapper.selectCount(any())).thenReturn(3L);
        when(tagMapper.deleteById(any(java.io.Serializable.class))).thenReturn(1);

        delete(3L)
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(0))
                .andExpect(MockMvcResultMatchers.jsonPath("$.data").value((Object) null));

        verify(tagMapper).deleteById(3L);
        Object[] audit = captureAudit();
        assertEquals("DELETE", audit[1]);
        assertEquals("org_tag", audit[2]);
        assertEquals(3L, audit[3]);
        assertTrue(String.valueOf(audit[4]).contains("\"name\":\"其他\""));
    }

    @Test
    void deleteMissingTagReturns404With1001() throws Exception {
        loginAs("ADMIN");
        when(tagMapper.selectList(any())).thenReturn(List.of());

        delete(999L)
                .andExpect(MockMvcResultMatchers.status().isNotFound())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1001))
                .andExpect(MockMvcResultMatchers.jsonPath("$.message").value("标签不存在"));

        verifyNoInteractions(tagRelMapper, jdbcTemplate);
    }

    @Test
    void salesCannotCreateRenameOrDelete() throws Exception {
        loginAs("SALES");

        post("{\"name\":\"测试标签\"}")
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1003));
        put(1L, "{\"name\":\"渠道\"}")
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1003));
        delete(1L)
                .andExpect(MockMvcResultMatchers.status().isForbidden())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1003));

        verifyNoInteractions(tagMapper, tagRelMapper, jdbcTemplate);
    }

    @Test
    void unauthenticatedMutationReturns1002() throws Exception {
        post("{\"name\":\"测试标签\"}")
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andExpect(MockMvcResultMatchers.jsonPath("$.code").value(1002));

        verifyNoInteractions(tagMapper, tagRelMapper, jdbcTemplate);
    }

    private ResultActions post(String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/tags")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions put(Long id, String body) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.put("/api/v1/tags/{id}", id)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private ResultActions delete(Long id) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.delete("/api/v1/tags/{id}", id));
    }

    private TagDtos.TagItem item(Long id, String name, int sort, boolean enabled, long orgCount) {
        return new TagDtos.TagItem(id, name, sort, enabled, orgCount);
    }

    private OrgTag tag(Long id, String name) {
        OrgTag tag = new OrgTag();
        tag.setId(id);
        tag.setName(name);
        tag.setSort(10);
        tag.setEnabled(true);
        return tag;
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
