package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 合作商（org）。
 */
@Data
@TableName("org")
public class Org {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 企业全称 */
    private String name;

    /** 简称 */
    private String shortName;

    /** 统一社会信用代码 */
    private String creditCode;

    /** 法定代表人 */
    private String legalPerson;

    /** 注册资本 */
    private String regCapital;

    /** 成立时间（当月 01 日） */
    private LocalDate foundDate;

    /** 行业 */
    private String industry;

    /** 城市 */
    private String city;

    /** 注册地址 */
    private String address;

    /** 网址 */
    private String website;

    /** 企业简介 */
    private String brief;

    /** 类型：TMC / 商旅分销 / 企业客户 / 其他 */
    private String type;

    /** 产品线（jsonb，暂以 String 承载） */
    private String productLines;

    /** 分级：战略 / 重点 / 普通 */
    private String level;

    /** 状态：潜在 / 试单 / 合作中 / 暂停 / 流失 */
    private String status;

    /** 归属销售 */
    private Long ownerId;

    /** 协作人（bigint[]，暂以 String 承载） */
    private String collaboratorIds;

    /** 创建人 */
    private Long createdBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删时间 */
    private LocalDateTime deletedAt;

}
