package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 审计日志（只增不改）（audit_log）。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 操作人 */
    private Long actorId;

    /** 动作 */
    private String action;

    /** 实体名 */
    private String entity;

    /** 实体 ID */
    private Long entityId;

    /** 变更内容（jsonb，暂以 String 承载） */
    private String diff;

    /** IP */
    private String ip;

    /** User-Agent */
    private String ua;

    /** 创建时间 */
    private LocalDateTime createdAt;

}
