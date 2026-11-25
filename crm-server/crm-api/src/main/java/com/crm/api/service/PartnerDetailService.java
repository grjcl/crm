package com.crm.api.service;

import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.PartnerDetailDtos.*;
import com.crm.api.security.AuthContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartnerDetailService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PartnerDetailService(JdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Detail get(long id, Integer recentFollows) {
        int requested = recentFollows == null ? 2 : recentFollows;
        if (requested < 0 || requested > 5) throw new BizException(ErrorCode.PARAM_INVALID, "recentFollows 必须在 0 到 5 之间");
        LocalDate first = LocalDate.now(SHANGHAI).withDayOfMonth(1);
        List<Main> mains = jdbc.query("select o.*, u.name owner_name, "
                + "array(select t.name from org_tag_rel r join org_tag t on t.id=r.tag_id where r.org_id=o.id order by t.sort,t.id) tag_names, "
                + "i.id invoice_id, i.title invoice_title, i.tax_no, i.bank, i.account, "
                + "s.id supply_id, s.supplier_type, s.cities, s.hotels, s.room_type, s.peak_guarantee, s.coop_mode, "
                + "s.coop_start, s.settle_type, s.credit_days, s.settle_contact, s.access_status, s.reject_reason, "
                + "p.id plan_id, p.plan_date, p.note plan_note, p.owner_id plan_owner_id, pu.name plan_owner_name, p.version, "
                + "(select count(*) from deal d where d.org_id=o.id and d.stage<4) open_deals, "
                + "(select count(*) from follow f where f.org_id=o.id and f.deleted_at is null and f.type<>'系统' "
                + "and f.created_at>=? and f.created_at<?) month_follows "
                + "from org o join sys_user u on u.id=o.owner_id left join org_invoice i on i.org_id=o.id "
                + "left join org_supply s on s.org_id=o.id left join follow_plan p on p.org_id=o.id "
                + "left join sys_user pu on pu.id=p.owner_id where o.id=? and o.deleted_at is null", (rs, n) -> new Main(
                rs.getLong("id"), rs.getString("name"), rs.getString("short_name"), rs.getString("credit_code"),
                rs.getString("legal_person"), rs.getString("reg_capital"), rs.getObject("found_date", LocalDate.class),
                rs.getString("industry"), rs.getString("city"), rs.getString("address"), rs.getString("website"),
                rs.getString("brief"), rs.getString("type"), readJson(rs.getString("product_lines")),
                rs.getString("level"), rs.getString("status"), rs.getString("settlement_contact"), time(rs, "created_at"),
                time(rs, "updated_at"), new User(rs.getLong("owner_id"), rs.getString("owner_name")),
                strings(rs.getArray("tag_names")),
                rs.getObject("invoice_id") == null ? null : new Invoice(rs.getString("invoice_title"), rs.getString("tax_no"),
                        rs.getString("bank"), mask(rs.getString("account"), 4, 4)),
                rs.getObject("supply_id") == null ? null : new Supply(strings(rs.getArray("supplier_type")), integer(rs, "cities"),
                        integer(rs, "hotels"), rs.getString("room_type"), rs.getString("peak_guarantee"), strings(rs.getArray("coop_mode")),
                        rs.getObject("coop_start", LocalDate.class), rs.getString("settle_type"), integer(rs, "credit_days"),
                        rs.getString("settle_contact"), rs.getString("access_status"), rs.getString("reject_reason")),
                rs.getObject("plan_id") == null ? null : new Plan(rs.getObject("plan_date", LocalDate.class), rs.getString("plan_note"),
                        new User(rs.getLong("plan_owner_id"), rs.getString("plan_owner_name")), rs.getInt("version")),
                rs.getLong("open_deals"), rs.getLong("month_follows")), first.atStartOfDay(SHANGHAI).toOffsetDateTime(),
                first.plusMonths(1).atStartOfDay(SHANGHAI).toOffsetDateTime(), id);
        if (mains.isEmpty()) throw new BizException(ErrorCode.PARAM_INVALID, "合作商不存在");
        Main m = mains.getFirst();
        List<User> collaborators = jdbc.query("select u.id,u.name from org o cross join lateral unnest(o.collaborator_ids) "
                + "with ordinality c(id,ord) join sys_user u on u.id=c.id where o.id=? order by c.ord",
                (rs, n) -> new User(rs.getLong(1), rs.getString(2)), id);
        List<String> tags = m.tags;
        Invoice invoice = m.invoice;
        Supply supply = tags.contains("供应") ? m.supply : null;
        List<Contact> contacts = jdbc.query("select * from contact where org_id=? order by is_primary desc,id",
                (rs, n) -> new Contact(rs.getLong("id"), rs.getString("name"), rs.getString("title"),
                        mask(rs.getString("phone"), 3, 4), rs.getString("role_tag"), rs.getBoolean("is_primary")), id);
        List<Attachment> attachments = jdbc.query("select * from attachment where org_id=? and deleted_at is null order by created_at desc,id desc",
                (rs, n) -> new Attachment(rs.getLong("id"), rs.getString("file_name"), rs.getString("mime"), rs.getLong("size"),
                        rs.getLong("uploaded_by"), time(rs, "created_at")), id);
        boolean admin = AuthContext.get() != null && "ADMIN".equals(AuthContext.get().role());
        List<Follow> follows = jdbc.query("select f.*, u.name creator_name from follow f join sys_user u on u.id=f.created_by "
                        + "where f.org_id=? order by (f.deleted_at is not null), f.created_at desc,f.id desc limit ?",
                (rs, n) -> {
                    boolean deleted = rs.getObject("deleted_at") != null;
                    return new Follow(rs.getLong("id"), rs.getString("type"), deleted ? "该记录已被删除" : rs.getString("content"),
                            new User(rs.getLong("created_by"), rs.getString("creator_name")), time(rs, "created_at"),
                            deleted, deleted && admin ? rs.getString("delete_reason") : null);
                }, id, Math.min(requested, 2));
        Plan plan = m.plan;
        Metrics metrics = new Metrics(m.openDeals, m.monthFollows, contacts.size(), plan == null ? null : plan.date());
        return new Detail(m.id, m.name, m.shortName, m.creditCode, m.legalPerson, m.regCapital, m.foundDate,
                m.industry, m.city, m.address, m.website, m.brief, m.type, m.productLines, m.level, m.status,
                m.settlementContact, m.createdAt, m.updatedAt, m.owner, collaborators, tags, invoice, supply,
                contacts, attachments, follows, plan, metrics);
    }

    private List<String> readJson(String value) throws SQLException {
        try { return json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception e) { throw new SQLException("Invalid product_lines", e); }
    }
    private static List<String> strings(Array array) throws SQLException {
        if (array == null) return List.of();
        try { return Arrays.asList((String[]) array.getArray()); } finally { array.free(); }
    }
    private static Integer integer(ResultSet rs, String column) throws SQLException { return rs.getObject(column, Integer.class); }
    private static OffsetDateTime time(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).atZoneSameInstant(SHANGHAI).toOffsetDateTime();
    }
    private static String mask(String value, int prefix, int suffix) {
        if (value == null || value.isEmpty()) return value;
        if (value.length() <= prefix + suffix) return "****";
        return value.substring(0, prefix) + "****" + value.substring(value.length() - suffix);
    }
    private record Main(long id, String name, String shortName, String creditCode, String legalPerson,
                        String regCapital, LocalDate foundDate, String industry, String city, String address,
                        String website, String brief, String type, List<String> productLines, String level,
                        String status, String settlementContact, OffsetDateTime createdAt, OffsetDateTime updatedAt, User owner,
                        List<String> tags, Invoice invoice, Supply supply, Plan plan, long openDeals, long monthFollows) {}
}
