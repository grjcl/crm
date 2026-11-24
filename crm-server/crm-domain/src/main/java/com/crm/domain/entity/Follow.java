package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 跟进记录（follow）。
 */
@Data
@TableName("follow")
public class Follow {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 合作商 ID */
    private Long orgId;

    /** 联系人 ID */
    private Long contactId;

    /** 商机 ID */
    private Long dealId;

    /** 类型：电话 / 拜访 / 微信 / 邮件 / 系统 */
    private String type;

    /** 跟进内容 */
    private String content;

    /** 可见范围 */
    private String visibleScope;

    /** 创建人 */
    private Long createdBy;

    /** 业务时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删时间 */
    private LocalDateTime deletedAt;

    /** 删除原因 */
    private String deleteReason;

    /** 语音地址（二期预留） */
    private String voiceUrl;

    /** 语音时长（二期预留） */
    private Integer voiceSec;

    /** 照片（jsonb，二期预留，暂以 String 承载） */
    private String photos;

    /** 纬度（二期预留） */
    private BigDecimal lat;

    /** 经度（二期预留） */
    private BigDecimal lng;

    /** 地址（二期预留） */
    private String addr;

}
