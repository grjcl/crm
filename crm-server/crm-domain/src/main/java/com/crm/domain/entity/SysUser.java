package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 系统用户（sys_user）。
 */
@Data
@TableName("sys_user")
public class SysUser {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 姓名 */
    private String name;

    /** 登录手机号 */
    private String phone;

    /** 工号 */
    private String employeeNo;

    /** 部门 */
    private String dept;

    /** 角色：SALES / ADMIN */
    private String role;

    /** 状态：ACTIVE / DISABLED */
    private String status;

    /** BCrypt 密码哈希 */
    private String passwordHash;

    /** 管理员重置密码时间 */
    private OffsetDateTime pwdChangedAt;

    /** 最后登录时间（timestamptz，保留时区偏移） */
    private OffsetDateTime lastLoginAt;

    /** 创建时间 */
    private OffsetDateTime createdAt;

    /** 更新时间 */
    private OffsetDateTime updatedAt;

}
