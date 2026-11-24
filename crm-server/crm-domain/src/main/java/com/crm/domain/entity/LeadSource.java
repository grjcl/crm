package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 来源字典（lead_source）。
 */
@Data
@TableName("lead_source")
public class LeadSource {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 来源名 */
    private String name;

    /** 展示顺序 */
    private Integer sort;

    /** 是否启用 */
    private Boolean enabled;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}
