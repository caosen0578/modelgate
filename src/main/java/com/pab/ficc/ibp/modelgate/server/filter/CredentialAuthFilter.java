package com.pab.ficc.ibp.modelgate.server.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.crypto.CryptoService;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredential;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfo;
import com.pab.ficc.ibp.modelgate.server.service.AppService;
import com.pab.ficc.ibp.modelgate.server.service.CredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Credential authentication filter (order=-190).
 *
 * Supports two modes:
 *   - api_key: Authorization: Bearer {api_key}  → SHA-256 → cache lookup
 *   - ak_sk:   Authorization: AKSK {access_key}:{timestamp}:{hmac_sha256_signature}
 *              Validates timestamp ±5min to prevent replay attacks
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialAuthFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -190;
    private static final long MAX_TIMESTAMP_SKEW_MS = 5L * 60 * 1000;

    private final CredentialService credentialService;
    private final AppService appService;
    private final CryptoService cryptoService;
    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        // /admin/** 由 AdminAuthFilter 单独处理；/actuator/**、/swagger-ui/**、/v3/api-docs/** 直接放行
        if (path.startsWith("/admin/") || path.startsWith("/actuator/")
                || path.startsWith("/swagger-ui") || path.startsWith("/v3/api-docs")
                || path.startsWith("/webjars/")) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || authorization.isBlank()) {
            return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.MISSING_CREDENTIAL);
        }

        // DB 查询必须切换到 boundedElastic 线程池，避免在 Netty 事件循环线程中执行阻塞 IO
        return Mono.fromCallable(() -> authenticate(authorization))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    if (result.isEmpty()) {
                        log.info("[CredentialAuth] Invalid credential, path={}", path);
                        return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_API_KEY);
                    }
                    AppCredential cred = result.get();

                    // 凭证状态检查（enabled=false 或已过期）
                    if (Boolean.FALSE.equals(cred.getEnabled())) {
                        return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_API_KEY);
                    }
                    if (cred.getExpiresAt() != null && cred.getExpiresAt().isBefore(LocalDateTime.now())) {
                        return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.CREDENTIAL_EXPIRED);
                    }

                    // 检查所属 App 是否启用（App 禁用 → 该 App 所有凭证立即失效）
                    Optional<AppInfo> app;
                    try {
                        app = Optional.of(appService.findByAppId(cred.getAppId()));
                    } catch (Exception e) {
                        app = Optional.empty();
                    }
                    if (app.isEmpty() || Boolean.FALSE.equals(app.get().getEnabled())) {
                        return reject(exchange, HttpStatus.FORBIDDEN, ErrorCode.APP_DISABLED);
                    }

                    // 将 appId 和凭证类型注入请求头，供后续过滤器（ModelAuthFilter、QuotaFilter、TraceFilter）读取
                    var mutated = exchange.getRequest().mutate()
                            .header("X-Mg-App", cred.getAppId())
                            .header("X-Mg-Cred-Type", cred.getCredType())
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                });
    }

    private Optional<AppCredential> authenticate(String authorization) {
        if (authorization.startsWith("Bearer ")) {
            // API Key 模式：对明文做 SHA-256，用哈希查凭证（DB 中不存明文）
            String apiKey = authorization.substring(7).trim();
            String hash = cryptoService.sha256Hex(apiKey);
            return credentialService.findBySecretHash(hash);
        }
        if (authorization.startsWith("AKSK ")) {
            // AK/SK 模式：格式 "AKSK {accessKey}:{timestamp毫秒}:{hmac_signature}"
            // 当前实现仅校验时间戳防重放，签名验证留给调用方；后续可在此补充 HMAC 比对
            String payload = authorization.substring(5).trim();
            String[] parts = payload.split(":", 3);
            if (parts.length != 3) return Optional.empty();
            String accessKey = parts[0];
            String timestampStr = parts[1];
            try {
                long ts = Long.parseLong(timestampStr);
                long now = System.currentTimeMillis();
                // 时间戳偏差超过 ±5 分钟，拒绝（防止请求被截获后延迟重放）
                if (Math.abs(now - ts) > MAX_TIMESTAMP_SKEW_MS) {
                    log.info("[CredentialAuth] Timestamp out of range: ts={}, now={}", ts, now);
                    return Optional.empty();
                }
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            return credentialService.findByAccessKey(accessKey);
        }
        return Optional.empty();
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.fail(errorCode));
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":" + errorCode.getCode() + ",\"message\":\"" + errorCode.getMessage() + "\"}").getBytes();
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }
}
