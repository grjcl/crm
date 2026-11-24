package com.crm.api.service;

import java.util.List;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.crm.api.common.PhoneMasker;
import com.crm.api.dto.UserBrief;
import com.crm.domain.entity.SysUser;
import com.crm.infra.mapper.SysUserMapper;
import org.springframework.stereotype.Service;

/**
 * 全员列表查询（GET /users）：只读、不分页，供协作人/交接/负责人的选人控件使用。
 */
@Service
public class UserQueryService {

    /** sys_user.status 取值（V1__init.sql） */
    private static final String STATUS_ACTIVE = "ACTIVE";

    /** q 最长有效长度（契约 maxLength: 40） */
    private static final int MAX_KEYWORD_LENGTH = 40;

    /**
     * 大小写不敏感匹配姓名/工号/手机号；q 中的 % 与 _ 按字面量处理。
     *
     * <p>契约只写 name / employee_no；验收步骤 6 要求「q=手机号」也能命中同事，
     * 因此一并匹配 phone（出参仍只给脱敏手机号）。</p>
     */
    private static final String KEYWORD_MATCH =
            "(name ILIKE {0} ESCAPE '\\' OR employee_no ILIKE {0} ESCAPE '\\'"
                    + " OR phone ILIKE {0} ESCAPE '\\')";

    private final SysUserMapper userMapper;

    public UserQueryService(SysUserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 查询用户列表。
     *
     * @param includeDisabled 是否包含 DISABLED；只有 ADMIN 传 enabled=false 时才会为 true
     * @param keyword         姓名/工号模糊匹配词，可为空
     * @return 按姓名升序的用户摘要（不含密码、明文手机号）
     */
    public List<UserBrief> list(boolean includeDisabled, String keyword) {
        LambdaQueryWrapper<SysUser> wrapper = new LambdaQueryWrapper<SysUser>()
                // 只取展示所需列，password_hash 不出库
                .select(SysUser::getId, SysUser::getName, SysUser::getDept,
                        SysUser::getEmployeeNo, SysUser::getRole, SysUser::getPhone)
                .orderByAsc(SysUser::getName);

        if (!includeDisabled) {
            wrapper.eq(SysUser::getStatus, STATUS_ACTIVE);
        }

        String match = normalizeKeyword(keyword);
        if (match != null) {
            wrapper.apply(KEYWORD_MATCH, "%" + escapeLike(match) + "%");
        }

        return userMapper.selectList(wrapper).stream()
                .map(this::toBrief)
                .toList();
    }

    private UserBrief toBrief(SysUser user) {
        return new UserBrief(
                user.getId(),
                user.getName(),
                user.getDept(),
                user.getEmployeeNo(),
                user.getRole(),
                PhoneMasker.mask(user.getPhone()));
    }

    /**
     * 去首尾空格；空串视为不过滤；超长按契约上限截断，避免超长 LIKE。
     */
    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmed = keyword.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return trimmed.length() > MAX_KEYWORD_LENGTH
                ? trimmed.substring(0, MAX_KEYWORD_LENGTH)
                : trimmed;
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
