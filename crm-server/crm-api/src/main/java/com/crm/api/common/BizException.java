package com.crm.api.common;

/**
 * 业务异常：携带业务码与 HTTP 状态，由 {@link GlobalExceptionHandler} 统一转换。
 */
public class BizException extends RuntimeException {

    private final int code;
    private final int httpStatus;

    public BizException(ErrorCode errorCode) {
        this(errorCode.getCode(), errorCode.getDefaultMessage(), 200);
    }

    public BizException(ErrorCode errorCode, String message) {
        this(errorCode.getCode(), message, 200);
    }

    public BizException(ErrorCode errorCode, String message, int httpStatus) {
        this(errorCode.getCode(), message, httpStatus);
    }

    public BizException(int code, String message, int httpStatus) {
        super(message);
        this.code = code;
        this.httpStatus = httpStatus;
    }

    public int getCode() {
        return code;
    }

    public int getHttpStatus() {
        return httpStatus;
    }
}
