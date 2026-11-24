package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 联系人（contact）。
 */
@Data
@TableName("contact")
public class Contact {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 合作商 ID */
    private Long orgId;

    /** 姓名 */
    private String name;

    /** 职务 */
    private String title;

    /** 手机或座机 */
    private String phone;

    /** 决策人 / 对接人 / 财务 */
    private String roleTag;

    /** 是否主联系人 */
    private Boolean isPrimary;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}
