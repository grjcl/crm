package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 采集渠道（channel）。
 */
@Data
@TableName("channel")
public class Channel {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 渠道名 */
    private String name;

    /** 渠道类型 */
    private String type;

    /** 网址或来源 */
    private String url;

    /** 关键词 */
    private String keyword;

    /** 频率 */
    private String frequency;

    /** 范围 */
    private String scope;

    /** 是否启用 */
    private Boolean enabled;

    /** 最后运行时间 */
    private LocalDateTime lastRunAt;

    /** 最后错误 */
    private String lastError;

    /** 累计入池条数 */
    private Integer totalFound;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}
