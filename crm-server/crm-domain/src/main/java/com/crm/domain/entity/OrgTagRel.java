package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 合作商-标签关联（org_tag_rel，复合主键 org_id + tag_id）。
 */
@Data
@TableName("org_tag_rel")
public class OrgTagRel {

    /** 合作商 ID */
    private Long orgId;

    /** 标签 ID */
    private Long tagId;

    /** 创建时间 */
    private LocalDateTime createdAt;

}
