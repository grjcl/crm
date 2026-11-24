package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 资源方扩展（org_supply）。
 */
@Data
@TableName("org_supply")
public class OrgSupply {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 合作商 ID */
    private Long orgId;

    /** 资源方类型（text[]，暂以 String 承载） */
    private String supplierType;

    /** 覆盖城市数 */
    private Integer cities;

    /** 合作酒店数 */
    private Integer hotels;

    /** 主要房型 */
    private String roomType;

    /** 旺季保障 */
    private String peakGuarantee;

    /** 合作模式（text[]，暂以 String 承载） */
    private String coopMode;

    /** 合作起始 */
    private LocalDate coopStart;

    /** 结算方式：预付 / 月结 */
    private String settleType;

    /** 账期天数 */
    private Integer creditDays;

    /** 结算联系人 */
    private String settleContact;

    /** 准入状态 */
    private String accessStatus;

    /** 淘汰原因 */
    private String rejectReason;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}
