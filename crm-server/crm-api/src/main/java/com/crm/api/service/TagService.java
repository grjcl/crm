package com.crm.api.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.TagDtos;
import com.crm.api.security.AuthContext;
import com.crm.domain.entity.OrgTag;
import com.crm.domain.entity.OrgTagRel;
import com.crm.infra.mapper.OrgTagMapper;
import com.crm.infra.mapper.OrgTagRelMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 标签字典读写（BE-API-019~022）。
 *
 * <p>列表用一条 left join + group by 查询同时取回标签与未软删合作商引用数，避免 N+1；
 * 增删改仅 ADMIN，写 audit_log（CREATE/UPDATE/DELETE）。</p>
 */
@Service
public class TagService {

    /** org_tag.name 为 varchar(10)，标签名上限 10 字。 */
    static final int MAX_NAME_LENGTH = 10;
    private static final int FIRST_SORT = 10;
    private static final int SORT_STEP = 10;
    private static final String ENTITY = "org_tag";

    /**
     * 一次查询取标签与引用数：只统计 deleted_at is null 的合作商。
     */
    private static final String LIST_SQL = """
            select t.id, t.name, t.sort, t.enabled, count(o.id) as org_count
            from org_tag t
            left join org_tag_rel r on r.tag_id = t.id
            left join org o on o.id = r.org_id and o.deleted_at is null
            group by t.id, t.name, t.sort, t.enabled
            order by t.sort asc, t.id asc
            """;

    private static final String NEXT_SORT_SQL =
            "select coalesce(max(sort), 0) + " + SORT_STEP + " from org_tag";

    /**
     * audit_log.diff 是 jsonb，JDBC 的 setString 需显式 {@code ?::jsonb} 才能写入。
     */
    private static final String AUDIT_SQL =
            "insert into audit_log(actor_id, action, entity, entity_id, diff, ip, ua)"
                    + " values (?, ?, ?, ?, ?::jsonb, ?, ?)";

    private final OrgTagMapper tagMapper;
    private final OrgTagRelMapper tagRelMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public TagService(OrgTagMapper tagMapper,
                      OrgTagRelMapper tagRelMapper,
                      JdbcTemplate jdbcTemplate,
                      ObjectMapper objectMapper) {
        this.tagMapper = tagMapper;
        this.tagRelMapper = tagRelMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 标签列表，按 sort 升序（同 sort 按 id 升序），附带未软删合作商引用数。
     */
    public List<TagDtos.TagItem> list() {
        return jdbcTemplate.query(LIST_SQL, (resultSet, rowNum) -> new TagDtos.TagItem(
                resultSet.getLong("id"),
                resultSet.getString("name"),
                resultSet.getInt("sort"),
                resultSet.getBoolean("enabled"),
                resultSet.getLong("org_count")));
    }

    /**
     * 新增标签：sort = 当前最大值 + 10（空表从 10 开始），并写 CREATE 审计。
     */
    @Transactional
    public TagDtos.CreatedTag create(String rawName, HttpServletRequest request) {
        String name = normalizeName(rawName);
        ensureNameAvailable(name, null);

        OrgTag tag = new OrgTag();
        tag.setName(name);
        tag.setSort(nextSort());
        tag.setEnabled(true);
        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.DUPLICATE, "标签名称已存在");
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("name", name);
        writeAudit("CREATE", tag.getId(), diff, request);

        return new TagDtos.CreatedTag(tag.getId(), name, tag.getSort());
    }

    /**
     * 标签改名：id 不变，org_tag_rel 无需更新；重名（排除自己）返回 3001。
     */
    @Transactional
    public TagDtos.RenamedTag rename(Long id, String rawName, HttpServletRequest request) {
        String name = normalizeName(rawName);
        OrgTag existing = findById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签不存在", 404);
        }

        if (!name.equals(existing.getName())) {
            ensureNameAvailable(name, id);

            OrgTag patch = new OrgTag();
            patch.setId(id);
            patch.setName(name);
            try {
                tagMapper.updateById(patch);
            } catch (DuplicateKeyException exception) {
                throw new BizException(ErrorCode.DUPLICATE, "标签名称已存在");
            }

            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("oldName", existing.getName());
            diff.put("newName", name);
            writeAudit("UPDATE", id, diff, request);
        }

        return new TagDtos.RenamedTag(id, name);
    }

    /**
     * 删除标签：事务内统计 org_tag_rel 全部关联行（含软删合作商），有引用返回 3002；
     * 无引用则硬删 org_tag 并写 DELETE 审计。
     */
    @Transactional
    public void delete(Long id, HttpServletRequest request) {
        OrgTag existing = findById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签不存在", 404);
        }

        Long references = tagRelMapper.selectCount(
                new QueryWrapper<OrgTagRel>().eq("tag_id", id));
        long referenceCount = references == null ? 0L : references;
        if (referenceCount > 0) {
            throw new BizException(ErrorCode.TAG_IN_USE,
                    "该标签下还有 " + referenceCount + " 家合作商，请先移除");
        }

        Long total = tagMapper.selectCount(new QueryWrapper<>());
        if (total != null && total <= 1) {
            throw new BizException(ErrorCode.PARAM_INVALID, "至少保留一个标签");
        }

        tagMapper.deleteById(id);

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("name", existing.getName());
        writeAudit("DELETE", id, diff, request);
    }

    private String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请输入标签名称");
        }
        if (name.codePointCount(0, name.length()) > MAX_NAME_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "标签名称不能超过 " + MAX_NAME_LENGTH + " 字");
        }
        return name;
    }

    private void ensureNameAvailable(String name, Long excludeId) {
        QueryWrapper<OrgTag> query = new QueryWrapper<OrgTag>().eq("name", name);
        if (excludeId != null) {
            query.ne("id", excludeId);
        }
        Long duplicate = tagMapper.selectCount(query);
        if (duplicate != null && duplicate > 0) {
            throw new BizException(ErrorCode.DUPLICATE, "标签名称已存在");
        }
    }

    /**
     * 只取 id/name/sort/enabled：实体上的 createdAt/updatedAt 是 LocalDateTime，
     * 而 PG 列为 TIMESTAMPTZ，全列查询会因类型转换失败。
     */
    private OrgTag findById(Long id) {
        if (id == null) {
            return null;
        }
        List<OrgTag> rows = tagMapper.selectList(new QueryWrapper<OrgTag>()
                .select("id", "name", "sort", "enabled")
                .eq("id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int nextSort() {
        Integer max = jdbcTemplate.queryForObject(NEXT_SORT_SQL, Integer.class);
        return max == null ? FIRST_SORT : max;
    }

    private void writeAudit(String action, Long entityId, Map<String, Object> diff,
                            HttpServletRequest request) {
        AuthContext.AuthUser current = AuthContext.get();
        Long actorId = current == null ? null : current.userId();

        String json;
        try {
            json = objectMapper.writeValueAsString(diff);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("审计 diff 序列化失败", exception);
        }

        String ip = null;
        String ua = null;
        if (request != null) {
            ip = clientIp(request);
            String userAgent = request.getHeader("User-Agent");
            ua = userAgent != null && userAgent.length() > 200
                    ? userAgent.substring(0, 200) : userAgent;
        }

        jdbcTemplate.update(AUDIT_SQL, actorId, action, ENTITY, entityId, json, ip, ua);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
