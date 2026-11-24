package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 下次跟进计划（follow_plan）。
 */
@Data
@TableName("follow_plan")
public class FollowPlan {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 合作商 ID */
    private Long orgId;

    /** 计划日期 */
    private LocalDate planDate;

    /** 计划事项 */
    private String note;

    /** 负责人 */
    private Long ownerId;

    /** 乐观锁版本 */
    private Integer version;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}
