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
            log.warn("[ModelGate] 请求被拒绝: {} {} -> {} | reason={} | cause={}",
                    method, path, status.value(), reason, rootCause);
            result = Result.fail(String.valueOf(status.value()),
                    rootCause != null ? reason + ": " + rootCause : reason);
        } else if (ex instanceof BusinessException be) {
            status = HttpStatus.valueOf(be.getErrorCode().getCode() < 600
                    ? be.getErrorCode().getCode() : HttpStatus.BAD_REQUEST.value());
            log.warn("[ModelGate] 业务异常: {} {} -> code={}, message={}",
                    method, path, be.getErrorCode().getCode(), be.getErrorCode().getMessage());
            result = Result.fail(String.valueOf(be.getErrorCode().getCode()), be.getErrorCode().getMessage());
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
