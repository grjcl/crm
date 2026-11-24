package com.crm.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 附件（attachment）。
 */
@Data
@TableName("attachment")
public class Attachment {

    /** 主键 */
    @TableId(type = IdType.AUTO)
    private Long id;

    /** 合作商 ID */
    private Long orgId;

    /** OSS object key */
    private String fileKey;

    /** 原始文件名 */
    private String fileName;

    /** MIME 类型 */
    private String mime;

    /** 字节数 */
    private Long size;

    /** 上传人 */
    private Long uploadedBy;

    /** 创建时间 */
    private LocalDateTime createdAt;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 软删时间 */
    private LocalDateTime deletedAt;

}
