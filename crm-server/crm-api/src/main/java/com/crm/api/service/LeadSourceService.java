package com.crm.api.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.LeadSourceDtos;
import com.crm.api.security.AuthContext;
import com.crm.domain.entity.Lead;
import com.crm.domain.entity.LeadSource;
import com.crm.infra.mapper.LeadMapper;
import com.crm.infra.mapper.LeadSourceMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 来源字典读写（BE-API-050~052）：仅管理员可增删改，读取走 GET /meta/dicts。
 */
@Service
public class LeadSourceService {

    /**
     * 来源名长度上限。shared/db 的 {@code lead_source.name varchar(10)} 与 R-VAL-28 均为 10 字，
     * 任务单里的 1–20 无法在库宽 10 上成立（不能改 shared/db），故按契约取 10。
     */
    static final int MAX_NAME_LENGTH = 10;
    private static final int FIRST_SORT = 10;
    private static final int SORT_STEP = 10;
    private static final String ENTITY = "lead_source";
    private static final String AUDIT_SQL =
            "insert into audit_log(actor_id, action, entity, entity_id, diff, ip, ua)"
                    + " values (?, ?, ?, ?, ?::jsonb, ?, ?)";

    private final LeadSourceMapper leadSourceMapper;
    private final LeadMapper leadMapper;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LeadSourceService(LeadSourceMapper leadSourceMapper,
                             LeadMapper leadMapper,
                             JdbcTemplate jdbcTemplate,
                             ObjectMapper objectMapper) {
        this.leadSourceMapper = leadSourceMapper;
        this.leadMapper = leadMapper;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    /**
     * 新增来源，sort 为当前最大值 + 10（空表从 10 开始），并写 CREATE 审计。
     */
    @Transactional
    public LeadSourceDtos.CreatedSource create(String rawName, HttpServletRequest request) {
        String name = normalizeName(rawName);
        ensureNameAvailable(name, null);

        LeadSource source = new LeadSource();
        source.setName(name);
        source.setSort(nextSort());
        try {
            leadSourceMapper.insert(source);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.DUPLICATE, "来源名称已存在");
        }

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("name", name);
        writeAudit("CREATE", source.getId(), diff, request);

        return new LeadSourceDtos.CreatedSource(source.getId(), name, source.getSort());
    }

    /**
     * 改名（id 不变，历史 lead 通过 source_id 自动展示新名），并写 UPDATE 审计（oldName/newName）。
     */
    @Transactional
    public LeadSourceDtos.RenamedSource rename(Long id, String rawName, HttpServletRequest request) {
        String name = normalizeName(rawName);
        LeadSource existing = findById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "来源不存在");
        }

        if (!name.equals(existing.getName())) {
            ensureNameAvailable(name, id);

            LeadSource patch = new LeadSource();
            patch.setId(id);
            patch.setName(name);
            try {
                leadSourceMapper.updateById(patch);
            } catch (DuplicateKeyException exception) {
                throw new BizException(ErrorCode.DUPLICATE, "来源名称已存在");
            }

            Map<String, Object> diff = new LinkedHashMap<>();
            diff.put("oldName", existing.getName());
            diff.put("newName", name);
            writeAudit("UPDATE", id, diff, request);
        }

        return new LeadSourceDtos.RenamedSource(id, name);
    }

    /**
     * 删除来源：被 lead 引用时返回 3002 并带引用数；只剩一个来源时拒绝删除；否则硬删并写 DELETE 审计。
     */
    @Transactional
    public void delete(Long id, HttpServletRequest request) {
        LeadSource existing = findById(id);
        if (existing == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "来源不存在");
        }

        Long references = leadMapper.selectCount(new QueryWrapper<Lead>()
                .eq("source_id", id));
        long referenceCount = references == null ? 0L : references;
        if (referenceCount > 0) {
            throw new BizException(ErrorCode.TAG_IN_USE,
                    "该来源下还有 " + referenceCount + " 条发现记录，请先处理");
        }

        Long total = leadSourceMapper.selectCount(new QueryWrapper<>());
        if (total != null && total <= 1) {
            throw new BizException(ErrorCode.TAG_IN_USE, "至少保留一个来源");
        }

        leadSourceMapper.deleteById(id);

        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("name", existing.getName());
        writeAudit("DELETE", id, diff, request);
    }

    private String normalizeName(String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (name.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请输入来源名称");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "来源名称不能超过 " + MAX_NAME_LENGTH + " 字");
        }
        return name;
    }

    private void ensureNameAvailable(String name, Long excludeId) {
        QueryWrapper<LeadSource> query = new QueryWrapper<LeadSource>()
                .eq("name", name);
        if (excludeId != null) {
            query.ne("id", excludeId);
        }
        Long duplicate = leadSourceMapper.selectCount(query);
        if (duplicate != null && duplicate > 0) {
            throw new BizException(ErrorCode.DUPLICATE, "来源名称已存在");
        }
    }

    /**
     * 只取 id/name：实体上的 created_at/updated_at 是 TIMESTAMPTZ，按 LocalDateTime 读取会失败。
     */
    private LeadSource findById(Long id) {
        List<LeadSource> rows = leadSourceMapper.selectList(new QueryWrapper<LeadSource>()
                .select("id", "name")
                .eq("id", id));
        return rows.isEmpty() ? null : rows.get(0);
    }

    private int nextSort() {
        List<LeadSource> rows = leadSourceMapper.selectList(new QueryWrapper<LeadSource>()
                .select("sort")
                .orderByDesc("sort")
                .last("limit 1"));
        Integer max = rows.isEmpty() ? null : rows.get(0).getSort();
        return max == null ? FIRST_SORT : max + SORT_STEP;
    }

    /**
     * audit_log.diff 是 jsonb，JDBC 的 setString 需显式 {@code ?::jsonb} 才能写入。
     */
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
