package com.crm.api.common;

/**
 * 统一错误码（PRD §13.4）。
 */
public enum ErrorCode {

    SUCCESS(0, "ok"),
    PARAM_INVALID(1001, "参数校验失败"),
    UNAUTHORIZED(1002, "未登录或登录已过期"),
    FORBIDDEN(1003, "没有权限执行该操作"),
    ACCOUNT_NOT_OPENED(2001, "手机号未开通"),
    BAD_CREDENTIALS(2002, "手机号或密码错误"),
    ACCOUNT_LOCKED(2003, "密码错误次数过多，请 15 分钟后再试"),
    DUPLICATE(3001, "名称或信用代码重复"),
    TAG_IN_USE(3002, "标签仍被引用，不能删除"),
    VERSION_CONFLICT(4001, "计划保存失败：版本冲突"),
    INTERNAL_ERROR(5000, "服务器内部错误");

    private final int code;
    private final String defaultMessage;

    ErrorCode(int code, String defaultMessage) {
        this.code = code;
        this.defaultMessage = defaultMessage;
    }

    public int getCode() {
        return code;
    }

    public String getDefaultMessage() {
        return defaultMessage;
    }
}
