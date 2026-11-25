package com.crm.api.service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.common.PageQuery;
import com.crm.api.common.PageResult;
import com.crm.api.dto.PartnerListDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class PartnerListService {
    private final JdbcTemplate jdbc;
    private String collation = "C";

    public PartnerListService(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @jakarta.annotation.PostConstruct
    public void detectCollation() {
        Boolean available = jdbc.queryForObject("select exists (select 1 from pg_collation "
                + "where collname = 'zh-x-icu' and pg_collation_is_visible(oid))", Boolean.class);
        if (Boolean.TRUE.equals(available)) {
            collation = "zh-x-icu";
        } else {
            org.slf4j.LoggerFactory.getLogger(PartnerListService.class)
                    .warn("zh-x-icu collation unavailable; falling back to C ordering");
        }
    }

    public PageResult<PartnerListDtos.Item> list(String tag, String city, String level, String status,
            String type, String q, Long owner, PageQuery page) {
        validate(level, Set.of("战略", "重点", "普通"));
        validate(status, Set.of("潜在", "试单", "合作中", "暂停", "流失"));
        validate(type, Set.of("TMC", "商旅分销", "企业客户", "其他"));

        StringBuilder where = new StringBuilder(" where true");
        List<Object> args = new ArrayList<>();
        if (tag != null && !tag.equals("全部")) {
            where.append(" and exists (select 1 from org_tag_rel r join org_tag t on t.id=r.tag_id where r.org_id=v.id and t.name=?)");
            args.add(tag);
        }
        addFilter(where, args, "v.city", city);
        addFilter(where, args, "v.level", level);
        addFilter(where, args, "v.status", status);
        addFilter(where, args, "o.type", type);
        if (owner != null) { where.append(" and v.owner_id=?"); args.add(owner); }
        if (q != null && !q.trim().isEmpty()) {
            String pattern = "%" + q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
            where.append(" and (v.name ilike ? escape '\\' or v.short_name ilike ? escape '\\')");
            args.add(pattern); args.add(pattern);
        }
        // Count and page share the same filtered CTE, including when the requested page is empty.
        String sql = "with filtered as (select v.*, o.type, o.industry, u.name as owner_name "
                + "from v_partner_list v join org o on o.id=v.id join sys_user u on u.id=v.owner_id"
                + where + "), total as (select count(*) as n from filtered), paged as (select * from filtered "
                + "order by (plan_date is null), plan_date, name collate \"zh-x-icu\", id limit ? offset ?) "
                + "select total.n, paged.* from total left join paged on true "
                + "order by (paged.plan_date is null), paged.plan_date, paged.name collate \"zh-x-icu\", paged.id";
        args.add(page.size()); args.add(page.offset());
        List<Row> rows = new ArrayList<>();
        long[] total = {0};
        org.springframework.jdbc.core.RowCallbackHandler mapper = rs -> {
            total[0] = rs.getLong("n");
            long id = rs.getLong("id");
            if (!rs.wasNull()) rows.add(new Row(id, rs.getString("name"), rs.getString("short_name"),
                    rs.getString("level"), rs.getString("status"), rs.getString("city"), rs.getString("type"),
                    rs.getString("industry"), rs.getLong("owner_id"), rs.getString("owner_name"),
                    date(rs.getDate("plan_date")), rs.getString("plan_note")));
        };
        jdbc.query(sql.replace("\"zh-x-icu\"", "\"" + collation + "\""), mapper, args.toArray());
        if (rows.isEmpty()) return PageResult.of(List.of(), total[0], page);

        String placeholders = String.join(",", java.util.Collections.nCopies(rows.size(), "?"));
        Object[] ids = rows.stream().map(Row::id).toArray();
        Map<Long, List<String>> tags = new HashMap<>();
        jdbc.query("select r.org_id, t.name from org_tag_rel r join org_tag t on t.id=r.tag_id "
                + "where r.org_id in (" + placeholders + ") order by t.sort, t.id", rs -> {
                tags.computeIfAbsent(rs.getLong("org_id"), ignored -> new ArrayList<>()).add(rs.getString("name"));
            }, ids);
        Map<Long, LocalDate> follows = new HashMap<>();
        jdbc.query("select org_id, (max(created_at) at time zone 'Asia/Shanghai')::date as last_follow "
                + "from follow where deleted_at is null and org_id in (" + placeholders + ") group by org_id", rs -> {
                follows.put(rs.getLong("org_id"), date(rs.getDate("last_follow")));
            }, ids);
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Shanghai"));
        List<PartnerListDtos.Item> items = rows.stream().map(row -> {
            long days = row.planDate == null ? 0 : ChronoUnit.DAYS.between(today, row.planDate);
            PartnerListDtos.Plan plan = row.planDate == null ? null :
                    new PartnerListDtos.Plan(row.planDate, row.planNote, Math.max(0, -days), days);
            return new PartnerListDtos.Item(row.id, row.name, row.shortName, row.level, row.status, row.city,
                    row.type, row.industry, new PartnerListDtos.Owner(row.ownerId, row.ownerName),
                    tags.getOrDefault(row.id, List.of()), follows.get(row.id), plan);
        }).toList();
        return PageResult.of(items, total[0], page);
    }

    private static void validate(String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) throw new BizException(ErrorCode.PARAM_INVALID, "筛选值非法");
    }
    private static void addFilter(StringBuilder where, List<Object> args, String column, String value) {
        if (value != null) { where.append(" and ").append(column).append("=?"); args.add(value); }
    }
    private static LocalDate date(Date date) { return date == null ? null : date.toLocalDate(); }
    private record Row(long id, String name, String shortName, String level, String status, String city,
                       String type, String industry, long ownerId, String ownerName,
                       LocalDate planDate, String planNote) {}
}
