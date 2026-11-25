package com.crm.api.service;

import java.util.List;
import java.util.stream.IntStream;

import com.crm.api.common.BizException;
import com.crm.api.common.ErrorCode;
import com.crm.api.dto.PartnerDedupDtos;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

@Service
public class PartnerDedupService {
    private static final String FULLWIDTH = IntStream.rangeClosed(0xff01, 0xff5e)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    private static final String ASCII = IntStream.rangeClosed(0x21, 0x7e)
            .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    // Peel innermost parenthesized groups recursively after translating full-width ASCII.
    // Input and stored names travel through exactly the same normalization pipeline.
    private static final String STRIP_GROUPS = "regexp_replace(value, '\\([^()]*\\)', '', 'g')";
    private static final String FINISH_NAME = "translate(regexp_replace(value, '[[:space:]　]+', '', 'g'), "
            + "'ABCDEFGHIJKLMNOPQRSTUVWXYZ', 'abcdefghijklmnopqrstuvwxyz')";
    private static final String CREDIT_SQL = "select o.id, o.name, u.name as owner, o.level, 1.0 as similarity "
            + "from org o join sys_user u on u.id = o.owner_id "
            + "where o.deleted_at is null and o.credit_code = ? order by o.id limit 5";
    private static final String NAME_SQL = "with recursive names(id, name, owner, level, value) as ("
            + "select null::bigint, null::text, null::text, null::text, translate(cast(? as text), ?, ?) "
            + "union all select o.id, o.name::text, u.name::text, o.level::text, translate(o.name, ?, ?) "
            + "from org o join sys_user u on u.id = o.owner_id where o.deleted_at is null), "
            + "stripped(id, name, owner, level, value) as (select * from names union all "
            + "select id, name, owner, level, " + STRIP_GROUPS + " from stripped "
            + "where value ~ '\\([^()]*\\)'), normalized as (select id, name, owner, level, "
            + FINISH_NAME + " as value from stripped where value !~ '\\([^()]*\\)'), "
            + "candidates as (select n.id, n.name, n.owner, n.level, similarity(n.value, i.value) as similarity "
            + "from normalized n cross join normalized i where n.id is not null and i.id is null) "
            + "select * from candidates where similarity > 0.9 order by similarity desc, id limit 5";
    private static final RowMapper<PartnerDedupDtos.Match> MATCH_MAPPER = (rs, row) ->
            new PartnerDedupDtos.Match(rs.getLong("id"), rs.getString("name"), rs.getString("owner"),
                    rs.getString("level"), rs.getDouble("similarity"));

    private final JdbcTemplate jdbcTemplate;

    public PartnerDedupService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public PartnerDedupDtos.Result check(String name, String creditCode) {
        if (name == null || name.codePoints().allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请输入合作商名称");
        }
        if (creditCode != null && !creditCode.codePoints()
                .allMatch(c -> Character.isWhitespace(c) || Character.isSpaceChar(c))) {
            List<PartnerDedupDtos.Match> matches = jdbcTemplate.query(CREDIT_SQL, MATCH_MAPPER, creditCode);
            if (!matches.isEmpty()) {
                return new PartnerDedupDtos.Result(true, "CREDIT_CODE", matches);
            }
        }
        List<PartnerDedupDtos.Match> matches = jdbcTemplate.query(NAME_SQL, MATCH_MAPPER,
                name, FULLWIDTH, ASCII, FULLWIDTH, ASCII);
        return new PartnerDedupDtos.Result(!matches.isEmpty(), matches.isEmpty() ? null : "NAME_LIKE", matches);
    }
}
