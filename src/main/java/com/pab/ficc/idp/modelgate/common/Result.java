package com.pab.ficc.idp.modelgate.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;

import java.time.Instant;

/**
 * 统一响应结构
 * <pre>
 * {
 *   "code": 0,
 *   "message": "success",
 *   "data": {...},
 *   "timestamp": 1713000000000,
 *   "traceId": "abc123"
 * }
 * </pre>
 */
@Getter
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> {

    private final int code;
    private final String message;
    private final T data;
    private final long timestamp;
    private final String traceId;

    private Result(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.timestamp = Instant.now().toEpochMilli();
        this.traceId = traceId;
    }

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data, TraceContext.get());
    }

    public static <T> Result<T> ok() {
        return ok(null);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null, TraceContext.get());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String detail) {
        String msg = errorCode.getMessage() + (detail != null ? ": " + detail : "");
        return new Result<>(errorCode.getCode(), msg, null, TraceContext.get());
    }

    public static <T> Result<T> fail(int code, String message) {
        return new Result<>(code, message, null, TraceContext.get());
    }
}
