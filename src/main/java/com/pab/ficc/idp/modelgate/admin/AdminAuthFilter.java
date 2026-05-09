package com.pab.ficc.idp.modelgate.admin;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.idp.modelgate.common.ErrorCode;
import com.pab.ficc.idp.modelgate.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Objects;

/**
 * Admin 接口鉴权过滤器（order = -200，最先执行）
 * <p>
 * 保护路径：/admin/** 和 /actuator/**（health/info 除外）
 * <p>
 * 三层鉴权（依次）：
 *  1. IP 白名单（配置了才校验）
 *  2. X-Admin-Token Header
 *  3. Basic Auth（Authorization: Basic xxx）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AdminAuthFilter implements GlobalFilter, Ordered {

    private static final String ADMIN_TOKEN_HEADER = "X-Admin-Token";
    private static final List<String> PROTECTED_PREFIXES = List.of("/admin/", "/actuator/");
    private static final List<String> PUBLIC_ACTUATOR_PATHS = List.of("/actuator/health", "/actuator/info");

    private final AdminAuthProperties props;
    private final IpMatcher ipMatcher;
    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return -200;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String path = request.getURI().getPath();

        // 放行非 Admin 路径
        if (!isProtected(path)) {
            return chain.filter(exchange);
        }

        // 放行健康检查（K8s 探针）
        if (isPublicActuator(path)) {
            return chain.filter(exchange);
        }

        String clientIp = getClientIp(request);

        // 1. IP 白名单校验
        if (!props.getAllowedIps().isEmpty() && !ipMatcher.matches(clientIp, props.getAllowedIps())) {
            log.warn("[AdminAuth] IP rejected: ip={}, path={}", clientIp, path);
            return reject(exchange, HttpStatus.FORBIDDEN, ErrorCode.FORBIDDEN_IP);
        }

        // 2. Admin Token 校验
        String adminToken = request.getHeaders().getFirst(ADMIN_TOKEN_HEADER);
        if (adminToken != null) {
            String tokenOwner = validateToken(adminToken);
            if (tokenOwner == null) {
                log.warn("[AdminAuth] Invalid token: path={}", path);
                return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_ADMIN_TOKEN);
            }
            log.debug("[AdminAuth] Token OK, owner={}, path={}", tokenOwner, path);
            ServerHttpRequest mutated = request.mutate()
                    .header("X-Admin-User", tokenOwner).build();
            return chain.filter(exchange.mutate().request(mutated).build());
        }

        // 3. Basic Auth 校验
        if (props.getBasicAuth().isEnabled()) {
            String basicUser = validateBasicAuth(request.getHeaders());
            if (basicUser != null) {
                log.debug("[AdminAuth] BasicAuth OK, user={}, path={}", basicUser, path);
                ServerHttpRequest mutated = request.mutate()
                        .header("X-Admin-User", basicUser).build();
                return chain.filter(exchange.mutate().request(mutated).build());
            }
        }

        log.warn("[AdminAuth] No valid credential: ip={}, path={}", clientIp, path);
        return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.ADMIN_AUTH_REQUIRED);
    }

    // ===== 工具方法 =====

    private boolean isProtected(String path) {
        return PROTECTED_PREFIXES.stream().anyMatch(path::startsWith);
    }

    private boolean isPublicActuator(String path) {
        return PUBLIC_ACTUATOR_PATHS.stream().anyMatch(path::equals);
    }

    private String validateToken(String token) {
        for (AdminAuthProperties.TokenEntry entry : props.getTokens()) {
            if (constantTimeEquals(token, entry.getToken())) {
                return entry.getName();
            }
        }
        return null;
    }

    private String validateBasicAuth(HttpHeaders headers) {
        String authHeader = headers.getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Basic ")) {
            return null;
        }
        try {
            String decoded = new String(Base64.getDecoder().decode(authHeader.substring(6)), StandardCharsets.UTF_8);
            String[] parts = decoded.split(":", 2);
            if (parts.length == 2
                    && constantTimeEquals(parts[0], props.getBasicAuth().getUsername())
                    && constantTimeEquals(parts[1], props.getBasicAuth().getPassword())) {
                return parts[0];
            }
        } catch (IllegalArgumentException e) {
            log.debug("[AdminAuth] Invalid Base64 in Authorization header");
        }
        return null;
    }

    /**
     * 常量时间字符串比较，防止时序攻击
     */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        int maxLen = Math.max(aBytes.length, bBytes.length);
        int diff = aBytes.length ^ bBytes.length;
        for (int i = 0; i < maxLen; i++) {
            diff |= (i < aBytes.length ? aBytes[i] : 0) ^ (i < bBytes.length ? bBytes[i] : 0);
        }
        return diff == 0;
    }

    private String getClientIp(ServerHttpRequest request) {
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeaders().getFirst("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp;
        }
        return Objects.requireNonNull(request.getRemoteAddress()).getAddress().getHostAddress();
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode) {
        exchange.getResponse().setStatusCode(status);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        if (status == HttpStatus.UNAUTHORIZED) {
            exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"ModelGate Admin\"");
        }

        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.fail(errorCode));
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":" + errorCode.getCode() + ",\"message\":\"" + errorCode.getMessage() + "\"}").getBytes();
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(bytes);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
