package com.pab.ficc.idp.modelgate.common;

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

/**
 * 全局异常处理器（WebFlux 版）
 * 统一将未捕获异常转换为标准 Result 响应格式
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

        if (ex instanceof ResponseStatusException rse) {
            status = HttpStatus.valueOf(rse.getStatus().value());
            result = Result.fail(status.value(), rse.getReason() != null ? rse.getReason() : ex.getMessage());
        } else if (ex instanceof BusinessException be) {
            status = HttpStatus.valueOf(be.getErrorCode().getCode() < 600
                    ? be.getErrorCode().getCode() : HttpStatus.BAD_REQUEST.value());
            result = Result.fail(be.getErrorCode());
        } else {
            log.error("[ModelGate] Unhandled exception: {}", ex.getMessage(), ex);
            result = Result.fail(ErrorCode.INTERNAL_ERROR);
        }

        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);

        // 写入 traceId 响应头
        String traceId = TraceContext.get();
        if (traceId != null) {
            exchange.getResponse().getHeaders().set(TraceContext.HEADER_TRACE_ID, traceId);
        }

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(result);
        } catch (JsonProcessingException e) {
            bytes = "{\"code\":500,\"message\":\"internal server error\"}".getBytes();
        }

        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
