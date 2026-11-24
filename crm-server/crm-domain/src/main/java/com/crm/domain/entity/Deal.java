package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 商机（deal）。
 */
@Data
@TableName("deal")
public class Deal {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 合作商 ID */
    private Long orgId;

    /** 商机标题 */
    private String title;

    /** 阶段：0 接洽 / 1 试单 / 2 报价 / 3 签约 / 4 赢单 */
    private Integer stage;

    /** 阶段变更时间 */
    private LocalDateTime stageUpdatedAt;

    /** 预计间夜 */
    private Integer expectNights;

    /** 预计 GMV */
    private BigDecimal expectGmv;

    /** 预计成交日期 */
    private LocalDate expectClose;

    /** 归属人 */
    private Long ownerId;

    /** 说明 */
    private String note;

    /** 结果：赢单 / 输单 */
    private String result;

    /** 输单原因 */
    private String lostReason;

    /** 关闭时间 */
    private LocalDateTime closedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}
