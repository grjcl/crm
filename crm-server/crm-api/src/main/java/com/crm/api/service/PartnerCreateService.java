package com.crm.api.service;

import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.PartnerCreateDtos;
import com.crm.api.security.AuthContext;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.text.Normalizer;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PartnerCreateService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final PartnerDedupService dedup;
    public PartnerCreateService(JdbcTemplate jdbc, ObjectMapper json, PartnerDedupService dedup) {
        this.jdbc = jdbc; this.json = json; this.dedup = dedup;
    }
    @Transactional
    public PartnerCreateDtos.Result create(PartnerCreateDtos.Request b, HttpServletRequest request) {
        var user = AuthContext.get();
        if (user == null) throw new BizException(ErrorCode.UNAUTHORIZED);
        if (b == null || blank(b.name()) || length(b.name()) < 2 || length(b.name()) > 100) invalid("name");
        max(b.shortName(),20,"shortName"); max(b.legalPerson(),30,"legalPerson"); max(b.regCapital(),30,"regCapital");
        max(b.industry(),30,"industry"); max(b.city(),20,"city"); max(b.address(),120,"address");
        max(b.website(),80,"website"); max(b.brief(),200,"brief"); max(b.settlementContact(),20,"settlementContact");
        if (!blank(b.settlementContact())) invalid("settlementContact 暂不支持：数据模型缺少字段");
        String credit = blank(b.creditCode()) ? null : b.creditCode();
        if (credit != null && !credit.matches("[0-9A-HJ-NPQRTUWXY]{2}\\d{6}[0-9A-HJ-NPQRTUWXY]{10}")) invalid("creditCode");
        LocalDate found = null;
        try { if (!blank(b.foundDate())) found = YearMonth.parse(b.foundDate()).atDay(1); }
        catch (RuntimeException e) { invalid("foundDate"); }
        String type = defaultValue(b.type(), "商旅分销"), level = defaultValue(b.level(), "重点"), status = defaultValue(b.status(), "潜在");
        oneOf(type, Set.of("TMC","商旅分销","企业客户","其他"),"type");
        oneOf(level, Set.of("战略","重点","普通"),"level");
        oneOf(status, Set.of("潜在","试单","合作中","暂停","流失"),"status");
        List<String> lines = values(b.productLines(), Set.of("协议价","包房","团体","机+酒"), "productLines");
        if (b.tags() == null || b.tags().isEmpty() || b.tags().contains(null)) invalid("tags");
        var tagIds = new LinkedHashSet<Long>();
        boolean supply = false;
        for (Object tag : b.tags()) {
            List<Map<String,Object>> rows;
            if (tag instanceof String name) rows = jdbc.queryForList("select id,name from org_tag where name=? and enabled=true for share", name);
            else if (tag instanceof Integer || tag instanceof Long) rows = jdbc.queryForList("select id,name from org_tag where id=? and enabled=true for share", ((Number) tag).longValue());
            else { invalid("tags"); return null; }
            if (rows.isEmpty()) invalid("tags");
            tagIds.add(((Number) rows.get(0).get("id")).longValue());
            supply |= "供应".equals(rows.get(0).get("name"));
        }
        var invoice = b.invoice();
        if (invoice != null) {
            max(invoice.title(),80,"invoice.title"); max(invoice.bank(),60,"invoice.bank");
            if (!blank(invoice.taxNo()) && (length(invoice.taxNo()) < 15 || length(invoice.taxNo()) > 20)) invalid("invoice.taxNo");
            if (!blank(invoice.account()) && !invoice.account().matches("[0-9]{1,30}")) invalid("invoice.account");
        }
        var s = supply ? b.supply() : null;
        LocalDate start = null;
        if (s != null) {
            values(s.supplierType(), Set.of("酒店集团","单体酒店","TMC","批发商","同业"),"supply.supplierType");
            values(s.coopMode(), Set.of("协议价","包房","代理","散拼"),"supply.coopMode");
            max(s.roomType(),60,"supply.roomType"); max(s.peakGuarantee(),60,"supply.peakGuarantee");
            max(s.settleContact(),20,"supply.settleContact"); max(s.rejectReason(),100,"supply.rejectReason");
            if (s.cities() != null && s.cities() < 0 || s.hotels() != null && s.hotels() < 0) invalid("supply.count");
            if (s.settleType()!=null) oneOf(s.settleType(),Set.of("预付","月结"),"supply.settleType");
            if (s.accessStatus()!=null) oneOf(s.accessStatus(),Set.of("考察中","合作中","暂停","淘汰"),"supply.accessStatus");
            if (s.creditDays()!=null && s.creditDays()<0 || "月结".equals(s.settleType()) && s.creditDays()==null) invalid("supply.creditDays");
            if ("淘汰".equals(s.accessStatus()) && blank(s.rejectReason())) invalid("supply.rejectReason");
            try { if (!blank(s.coopStart())) start = LocalDate.parse(s.coopStart()); }
            catch (RuntimeException e) { invalid("supply.coopStart"); }
        }
        boolean admin = "ADMIN".equals(user.role());
        Long owner = admin && b.ownerId()!=null ? b.ownerId() : user.userId();
        List<Long> collaborators = admin && b.collaboratorIds()!=null ? new ArrayList<>(new LinkedHashSet<>(b.collaboratorIds())) : List.of();
        var users = new HashSet<>(collaborators); users.add(owner);
        for (Long id : users) {
            if (id == null || jdbc.queryForObject("select count(*) from sys_user where id=? and status='ACTIVE'", Integer.class, id) != 1) invalid("ownerId/collaboratorIds");
        }
        // Serialize create operations, including normalized-name checks, until commit.
        jdbc.queryForObject("select pg_advisory_xact_lock(731928314)", Object.class);
        var match = dedup.check(b.name(), credit);
        if (match.hit() && "CREDIT_CODE".equals(match.type())) throw new BizException(ErrorCode.DUPLICATE);
        // Check every active name: the suggestion API intentionally caps results at five.
        Boolean sameName = jdbc.query("select name from org where deleted_at is null", rs -> {
            String target = normalized(b.name());
            while (rs.next()) if (normalized(rs.getString(1)).equals(target)) return true;
            return false;
        });
        if (Boolean.TRUE.equals(sameName)) throw new BizException(ErrorCode.DUPLICATE);
        String shortName = blank(b.shortName()) ? b.name().substring(0,b.name().offsetByCodePoints(0,Math.min(4,length(b.name())))) : b.shortName();
        List<Long> inserted = jdbc.query("""
                insert into org(name,short_name,credit_code,legal_person,reg_capital,found_date,industry,city,address,website,brief,type,product_lines,level,status,owner_id,collaborator_ids,created_by)
                values (?,?,?,?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?::bigint[],?)
                on conflict (credit_code) where credit_code is not null and deleted_at is null do nothing returning id
                """, (rs, n) -> rs.getLong(1), b.name(),shortName,credit,b.legalPerson(),b.regCapital(),found,b.industry(),b.city(),b.address(),b.website(),b.brief(),type,serialize(lines),level,status,owner,pgArray(collaborators),user.userId());
        if (inserted.isEmpty()) throw new BizException(ErrorCode.DUPLICATE);
        Long id = inserted.get(0);
        for (Long tag : tagIds) jdbc.update("insert into org_tag_rel(org_id,tag_id) values (?,?)",id,tag);
        if (invoice != null) jdbc.update("insert into org_invoice(org_id,title,tax_no,bank,account) values (?,?,?,?,?)",id,
                blank(invoice.title()) ? b.name() : invoice.title(),invoice.taxNo(),invoice.bank(),invoice.account());
        if (s != null) jdbc.update("""
                insert into org_supply(org_id,supplier_type,cities,hotels,room_type,peak_guarantee,coop_mode,coop_start,settle_type,credit_days,settle_contact,access_status,reject_reason)
                values (?,?::text[],?,?,?,?,?::text[],?,?,?,?,?,?)
                """,id, pgArray(values(s.supplierType(),Set.of("酒店集团","单体酒店","TMC","批发商","同业"),"supplierType")),s.cities(),s.hotels(),s.roomType(),s.peakGuarantee(),pgArray(values(s.coopMode(),Set.of("协议价","包房","代理","散拼"),"coopMode")),start,s.settleType(),s.creditDays(),s.settleContact(),s.accessStatus(),s.rejectReason());
        Map<String,Object> diff = new LinkedHashMap<>();
        Map<String,Object> after = new LinkedHashMap<>(json.convertValue(b, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { }));
        after.put("shortName", shortName); after.put("creditCode", credit); after.put("type", type);
        after.put("level", level); after.put("status", status); after.put("productLines", lines);
        after.put("ownerId", owner); after.put("collaboratorIds", collaborators); after.put("tags", tagIds);
        after.put("supply", s);
        if (invoice != null && blank(invoice.title())) {
            Map<String,Object> savedInvoice = new LinkedHashMap<>(json.convertValue(invoice, new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>() { }));
            savedInvoice.put("title", b.name()); after.put("invoice", savedInvoice);
        }
        diff.put("after", after);
        diff.put("overridden", !admin && (b.ownerId()!=null && !b.ownerId().equals(owner) || b.collaboratorIds()!=null && !b.collaboratorIds().isEmpty()));
        String ua = request == null ? null : request.getHeader("User-Agent");
        if (ua != null && ua.length()>200) ua=ua.substring(0,200);
        jdbc.update("insert into audit_log(actor_id,action,entity,entity_id,diff,ip,ua) values (?,'CREATE','org',?,?::jsonb,?,?)",user.userId(),id,serialize(diff),request == null ? null : request.getRemoteAddr(),ua);
        return new PartnerCreateDtos.Result(id,b.name(),status);
    }
    private String serialize(Object o) { try { return json.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); } }
    private static String pgArray(Collection<?> values) { return "{" + values.stream().map(v -> "\""+v.toString().replace("\\","\\\\").replace("\"","\\\"")+"\"").reduce((a,b)->a+","+b).orElse("") + "}"; }
    private static String normalized(String s) {
        String value = Normalizer.normalize(s, Normalizer.Form.NFKC);
        String previous;
        do { previous = value; value = value.replaceAll("\\([^()]*\\)", ""); } while (!value.equals(previous));
        return value.replaceAll("[\\s\\p{Z}]+", "").toLowerCase(Locale.ROOT);
    }
    private static int length(String s) { return s.codePointCount(0,s.length()); }
    private static boolean blank(String s) { return s == null || s.isBlank(); }
    private static String defaultValue(String s,String d) { return blank(s) ? d : s; }
    private static void max(String s,int n,String field) { if (s != null && length(s)>n) invalid(field); }
    private static void invalid(String field) { throw new BizException(ErrorCode.PARAM_INVALID, field+" 无效"); }
    private static void oneOf(String s,Set<String> allowed,String field) { if (!allowed.contains(s)) invalid(field); }
    private static List<String> values(List<String> input,Set<String> allowed,String field) {
        if (input == null) return List.of();
        for (String s : input) if (s == null || !allowed.contains(s)) invalid(field);
        return new ArrayList<>(new LinkedHashSet<>(input));
    }
}
