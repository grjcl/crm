package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 待确认发现池（lead_pool）。
 */
@Data
@TableName("lead_pool")
public class LeadPool {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 企业名称 */
    private String orgName;

    /** 来源渠道 ID */
    private Long channelId;

    /** 来源说明 */
    private String sourceDetail;

    /** 抽取原文片段 */
    private String snippet;

    /** 城市 */
    private String city;

    /** 来源链接 */
    private String rawUrl;

    /** 去重指纹 */
    private String fingerprint;

    /** 采集时间 */
    private LocalDateTime foundAt;

    /** 状态：待确认 / 已认领 / 已丢弃 */
    private String status;

    /** 认领人 */
    private Long claimedBy;

    /** 认领时间 */
    private LocalDateTime claimedAt;

    /** 认领后生成的发现 ID */
    private Long leadId;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}
