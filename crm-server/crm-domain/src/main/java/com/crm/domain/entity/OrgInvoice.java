package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 开票信息（org_invoice）。
 */
@Data
@TableName("org_invoice")
public class OrgInvoice {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 合作商 ID */
    private Long orgId;

    /** 开票抬头 */
    private String title;

    /** 纳税人识别号 */
    private String taxNo;

    /** 开户银行 */
    private String bank;

    /** 银行账号 */
    private String account;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

}
