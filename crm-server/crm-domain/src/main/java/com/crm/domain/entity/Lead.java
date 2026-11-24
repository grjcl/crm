package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 我的发现（lead）。
 */
@Data
@TableName("lead")
public class Lead {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 企业名称 */
    private String orgName;

    /** 联系人姓名 */
    private String contactName;

    /** 联系电话 */
    private String phone;

    /** 来源字典 ID */
    private Long sourceId;

    /** 城市 */
    private String city;

    /** 状态：待跟进 / 已联系 / 已转合作商 / 已丢弃 */
    private String status;

    /** 归属人 */
    private Long ownerId;

    /** 转合作商后的合作商 ID */
    private Long orgId;

    /** 需求简述 */
    private String remark;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}
