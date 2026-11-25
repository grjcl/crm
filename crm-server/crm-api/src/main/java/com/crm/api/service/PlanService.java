package com.crm.api.service;

import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.PlanDtos;
import com.crm.api.security.AuthContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PlanService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PlanService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public PlanDtos.Plan get(Long orgId) {
        // One statement gives a consistent view of the organisation and its optional plan.
        List<PlanDtos.Plan> rows = jdbc.query("""
                select p.id, p.plan_date, p.note, p.version, p.updated_at, u.id as owner_id, u.name as owner_name
                from org o left join follow_plan p on p.org_id=o.id
                left join sys_user u on u.id=p.owner_id
                where o.id=? and o.deleted_at is null
                """, (rs, n) -> rs.getObject("id") == null ? null : new PlanDtos.Plan(
                        rs.getObject("plan_date", LocalDate.class), rs.getString("note"),
                        new PlanDtos.Owner(rs.getLong("owner_id"), rs.getString("owner_name")),
                        rs.getInt("version"), rs.getObject("updated_at", OffsetDateTime.class)
                                .withOffsetSameInstant(ZoneOffset.ofHours(8))), orgId);
        if (rows.isEmpty()) throw missing();
        return rows.get(0);
    }

    @Transactional
    public PlanDtos.Saved save(Long orgId, PlanDtos.Request body, HttpServletRequest request) {
        if (body == null || body.date() == null || body.note() == null || body.note().isEmpty()
                || body.note().codePointCount(0, body.note().length()) > 60
                || body.version() == null || body.version() < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "计划日期、事项或版本无效");
        }
        AuthContext.AuthUser user = AuthContext.get();
        if (user == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        // Serialize with deletion and competing first inserts. The version predicate below remains
        // necessary for writers that do not acquire this organisation lock.
        List<Long> orgs = jdbc.query("select id from org where id=? and deleted_at is null for update",
                (rs, n) -> rs.getLong(1), orgId);
        if (orgs.isEmpty()) throw missing();
        List<Existing> plans = jdbc.query("select id, plan_date, note, owner_id, version from follow_plan where org_id=? for update",
                (rs, n) -> new Existing(rs.getLong("id"), rs.getObject("plan_date", LocalDate.class),
                        rs.getString("note"), rs.getLong("owner_id"), rs.getInt("version")), orgId);
        Existing before = plans.isEmpty() ? null : plans.get(0);
        Long planId;
        if (before == null) {
            if (body.version() != 0) throw conflict();
            List<Long> inserted = jdbc.query("""
                    insert into follow_plan(org_id, plan_date, note, owner_id, version)
                    values (?, ?, ?, ?, 1) on conflict (org_id) do nothing returning id
                    """, (rs, n) -> rs.getLong(1), orgId, body.date(), body.note(), user.userId());
            if (inserted.isEmpty()) throw conflict();
            planId = inserted.get(0);
        } else {
            if (before.version() != body.version()) throw conflict();
            int changed = jdbc.update("""
                    update follow_plan set plan_date=?, note=?, version=version+1
                    where org_id=? and version=?
                    """, body.date(), body.note(), orgId, body.version());
            if (changed != 1) throw conflict();
            planId = before.id();
        }
        int version = body.version() + 1;
        Map<String, Object> diff = new LinkedHashMap<>();
        diff.put("before", before == null ? null : snapshot(before.date(), before.note(), before.ownerId(), before.version()));
        diff.put("after", snapshot(body.date(), body.note(), before == null ? user.userId() : before.ownerId(), version));
        try {
            String ua = request == null ? null : request.getHeader("User-Agent");
            if (ua != null && ua.length() > 200) ua = ua.substring(0, 200);
            String ip = request == null ? null : request.getRemoteAddr();
            jdbc.update("insert into audit_log(actor_id, action, entity, entity_id, diff, ip, ua) values (?, 'UPDATE', 'follow_plan', ?, ?::jsonb, ?, ?)",
                    user.userId(), planId, json.writeValueAsString(diff), ip, ua);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("审计序列化失败", e);
        }
        return new PlanDtos.Saved(orgId, body.date(), body.note(), version);
    }

    private static Map<String, Object> snapshot(LocalDate date, String note, Long ownerId, int version) {
        return Map.of("date", date.toString(), "note", note, "ownerId", ownerId, "version", version);
    }
    private static BizException missing() {
        return new BizException(ErrorCode.PARAM_INVALID, "合作商不存在");
    }
    private static BizException conflict() {
        return new BizException(ErrorCode.VERSION_CONFLICT, "计划已被他人修改，请刷新后重试");
    }
    private record Existing(Long id, LocalDate date, String note, Long ownerId, int version) { }
}
