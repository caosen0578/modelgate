package com.pab.ficc.ibp.modelgate.server.common;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

// 管理后台统一返回改用行内标准 Result（单类型 import 会 shadow 同包的旧 common.Result）
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.filter.TraceWebFilter;

/**
 * 全局异常处理器（WebFlux 版）
 * 统一将未捕获异常转换为行内标准 Result 响应格式（resultCode/resultMessage 结构）
 */
@Slf4j
@Order(-1)
@Component
public class GlobalExceptionHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public Mono<Void> handle(ServerWebExchange exchange, Throwable ex) {
        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        Result<?> result;

        // Spring 6：ServerHttpRequest.getMethodValue() 已移除，getMethod() 返回非空 HttpMethod
        String method = exchange.getRequest().getMethod().name();
        String path = exchange.getRequest().getURI().getPath();

        if (ex instanceof ResponseStatusException rse) {
            // Spring 6：ResponseStatusException.getStatus() 已移除，改为 getStatusCode()，
            // 返回的是 HttpStatusCode 接口而非 HttpStatus 枚举（可能是非标准码），故用 value() 转换
            status = HttpStatus.valueOf(rse.getStatusCode().value());
            // 请求体解码失败（字段类型不匹配、JSON 非法、参数校验不过）时，rse.getReason() 往往只有
            // "Failed to read HTTP message" 这类笼统描述，真正原因在 cause 链最深处，必须一并打出来，
            // 否则前端只看到一个 400、后端控制台一片空白，无从排查。
            String reason = rse.getReason() != null ? rse.getReason() : ex.getMessage();
            String rootCause = rootCauseMessage(ex);
            // 参数绑定失败要点名是哪个字段：WebExchangeBindException 的 reason 恒为
            // "Validation failure"，rootCause 也取不到有用信息，前端只能看到一句无从下手的
            // "Validation failure"。实测 ?isStream=abc / ?pageSize=abc / ?startTime=乱写
            // 三种情况返回的文案完全一样，排查时根本不知道该改哪个参数。
            String fieldDetail = bindFieldDetail(ex);
            if (fieldDetail != null) {
                reason = reason + " —— " + fieldDetail;
                rootCause = null;   // 字段级信息比 rootCause 的异常类名有用得多
            }
            log.warn("[ModelGate] 请求被拒绝: {} {} -> {} | reason={} | cause={}",
                    method, path, status.value(), reason, rootCause);
            result = Result.fail(String.valueOf(status.value()),
                    rootCause != null ? reason + ": " + rootCause : reason);
        } else if (ex instanceof BusinessException be) {
            status = HttpStatus.valueOf(be.getErrorCode().getCode() < 600
                    ? be.getErrorCode().getCode() : HttpStatus.BAD_REQUEST.value());
            // 用 be.getMessage() 而非 be.getErrorCode().getMessage()：
            // BusinessException(errorCode, detail) 把 detail 拼进了 Throwable 的 message，
            // 而 ErrorCode 里只有 "Invalid request parameter" 这类**泛化**文案。
            // 原先取后者，等于把所有调用点辛苦写的具体原因全部丢掉——
            // 前端只能看到"Invalid request parameter"，用户完全不知道该改什么。
            // 影响的不只是报文查询：ModelController 的 "modelId 不能为空" 等 3 处同样被吞掉过。
            String detail = be.getMessage() != null ? be.getMessage() : be.getErrorCode().getMessage();
            log.warn("[ModelGate] 业务异常: {} {} -> code={}, message={}",
                    method, path, be.getErrorCode().getCode(), detail);
            result = Result.fail(String.valueOf(be.getErrorCode().getCode()), detail);
        } else if (ex instanceof com.pab.ficc.ibp.modelgate.server.crypto.CryptoService.CryptoException ce) {
            // 解密失败属于「配置/数据问题」而非服务故障：最常见的就是当前 crypto.key
            // 与该凭证加密时的密钥不是同一把。此前它混在未捕获异常里，前端只看到
            // 500 "internal server error"，完全看不出是密钥不匹配还是密文损坏，
            // 只能靠翻服务端堆栈才知道是 AEADBadTagException——那不该是排查的起点。
            status = HttpStatus.BAD_REQUEST;
            log.warn("[ModelGate] 凭证解密失败: {} {} | {}", method, path, ce.getMessage());
            result = Result.fail(String.valueOf(ErrorCode.INVALID_PARAMETER.getCode()), ce.getMessage());
        } else {
            log.error("[ModelGate] 未捕获异常: {} {} -> {}", method, path, ex.getMessage(), ex);
            result = Result.fail(String.valueOf(ErrorCode.INTERNAL_ERROR.getCode()), ErrorCode.INTERNAL_ERROR.getMessage());
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // traceId 响应头由 TraceWebFilter 统一写入；此处仅在缺失时兜底
        // （异常若发生在 TraceWebFilter 之前，响应头还没来得及写）
        if (!exchange.getResponse().getHeaders().containsKey(TraceContext.HEADER_TRACE_ID)) {
            String traceId = exchange.getAttribute(TraceWebFilter.ATTR_TRACE_ID);
            if (traceId == null) {
                traceId = TraceContext.get();
            }
            if (traceId != null) {
                exchange.getResponse().getHeaders().set(TraceContext.HEADER_TRACE_ID, traceId);
            }
        }

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            bytes = "{\"resultCode\":\"500\",\"resultMessage\":\"internal server error\"}".getBytes();
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    /**
     * 从参数绑定异常里提取"哪个字段、传了什么、期望什么"。
     *
     * <p>不是绑定异常时返回 null。只取前 3 个字段：一次请求同时错 3 个以上参数极少见，
     * 全列出来只会把响应撑长。
     */
    private String bindFieldDetail(Throwable ex) {
        if (!(ex instanceof org.springframework.web.bind.support.WebExchangeBindException be)) {
            return null;
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (org.springframework.validation.FieldError fe : be.getFieldErrors()) {
            if (parts.size() >= 3) {
                break;
            }
            Object rejected = fe.getRejectedValue();
            parts.add(String.format("参数 %s 取值 '%s' 无法解析", fe.getField(), rejected));
        }
        return parts.isEmpty() ? null : String.join("; ", parts);
    }

    /**
     * 取异常链最深处的原因描述；无 cause 时返回 null。
     */
    private String rootCauseMessage(Throwable ex) {
        Throwable cause = ex.getCause();
        if (cause == null) {
            return null;
        }
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getClass().getSimpleName() + ": " + cause.getMessage();
    }
}
