package com.pab.ficc.ibp.modelgate.server.common;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 业务错误码枚举
 * <p>
 * 约定：
 *  0        — 成功
 *  4xx      — 客户端错误（对齐 HTTP 语义）
 *  5xx      — 服务端错误
 *  10xxx    — 业务自定义错误
 */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    // ===== 通用 =====
    SUCCESS(0, "success"),
    INTERNAL_ERROR(500, "internal server error"),

    // ===== 鉴权 =====
    MISSING_API_KEY(401, "Missing X-Api-Key header"),
    INVALID_API_KEY(401, "Invalid or inactive API key"),
    ADMIN_AUTH_REQUIRED(401, "Admin authentication required"),
    INVALID_ADMIN_TOKEN(401, "Invalid admin token"),
    FORBIDDEN_IP(403, "IP address not allowed"),

    // ===== 限流/配额 =====
    RATE_LIMIT_EXCEEDED(429, "Rate limit exceeded, please slow down"),
    QUOTA_EXCEEDED_QPS(429, "QPS quota exceeded for this app"),
    QUOTA_EXCEEDED_DAILY(429, "Daily request quota exceeded for this app"),
    QUOTA_EXCEEDED_MONTHLY(429, "Monthly token quota exceeded for this app"),

    // ===== 熔断 =====
    UPSTREAM_CIRCUIT_OPEN(503, "Upstream service temporarily unavailable"),
    UPSTREAM_TIMEOUT(504, "Upstream service timed out"),

    // ===== 鉴权/访问控制 =====
    MISSING_CREDENTIAL(401, "Missing Authorization credential"),
    CREDENTIAL_EXPIRED(401, "Credential has expired"),
    REPLAY_ATTACK(401, "Request timestamp out of allowed range"),
    MODEL_NOT_AUTHORIZED(403, "App is not authorized to use this model"),
    APP_DISABLED(403, "App account is disabled"),
    MODEL_DISABLED(403, "Model is disabled"),
    AUTH_EXPIRED(403, "Authorization has expired"),
    ACCESS_DENIED(403, "Client IP or domain not allowed"),

    // ===== 模型路由 =====
    MODEL_NOT_FOUND(404, "Model not found"),
    MODEL_FIELD_MISSING(400, "Request body missing 'model' field"),

    // ===== 通用服务（/svc/** 流量）=====
    // 与模型侧一一对应但**分开定义**：两条链路的拒绝原因语义不同，
    // 复用 MODEL_* 会让调用方拿到 "Model not found" 却在调一个服务接口，极其误导。
    //
    // ⚠️ HTTP 状态取自本枚举的 code，拒绝时**不要写死 403**——
    // 模型侧踩过这个坑：写死 403 会造成「状态码 403、响应体 code:404」的自相矛盾，
    // 调用方按 403 去查授权与白名单，实际原因却是路径里的服务编码写错了。
    SERVICE_NOT_FOUND(404, "Service not found"),
    SERVICE_CODE_MISSING(404, "Request path missing service code, expected /svc/{serviceCode}/**"),
    SERVICE_DISABLED(503, "Service is disabled"),
    SERVICE_NOT_AUTHORIZED(403, "App is not authorized to use this service"),
    SERVICE_AUTH_EXPIRED(403, "Service authorization has expired"),

    // ===== 业务通用 =====
    INVALID_PARAMETER(10003, "Invalid request parameter"),
    RESOURCE_NOT_FOUND(10004, "Resource not found"),
    RESOURCE_ALREADY_EXISTS(10005, "Resource already exists"),
    ACTIVE_REFS_EXIST(10006, "Resource has active references and cannot be deleted"),

    // ===== 兼容旧版（保留勿删） =====
    WHITELIST_NOT_FOUND(10001, "Whitelist entry not found"),
    WHITELIST_ALREADY_EXISTS(10002, "API key already exists");

    private final int code;
    private final String message;
}
