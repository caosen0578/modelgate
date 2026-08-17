package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.crypto.CryptoService;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
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

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Optional;

/**
 * Credential authentication filter (order=-190).
 *
 * Supports two modes:
 *   - api_key: Authorization: Bearer {api_key}  → SHA-256 → cache lookup
 *   - ak_sk:   Authorization: AKSK {access_key}:{timestamp_ms}:{hmac_sha256_base64}
 *              Validates timestamp ±5min (replay prevention) AND verifies
 *              HMAC-SHA256(SK, accessKey + ":" + timestamp) to prove SK possession.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CredentialAuthFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -190;
    private static final long MAX_TIMESTAMP_SKEW_MS = 5L * 60 * 1000;

    /**
     * 鉴权结果写入 exchange attribute 供下游读取。
     *
     * <p>此前只写请求头 X-Mg-App，而 TraceFilter 前移到 -200 后先于本过滤器执行，
     * 只能在 doFinally 里回读——请求头挂在 mutate 出来的新 request 上、原 exchange 读不到，
     * 而 attribute 在 mutate 前后是同一份，因此必须走 attribute。
     * 请求头照旧保留：ModelAuthFilter / QuotaFilter 仍从头里取。
     */
    public static final String ATTR_APP_ID = "mg.appId";
    public static final String ATTR_CRED_TYPE = "mg.credType";

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
        // 管理面（/modelgate/**）走内网逐接口鉴权，不在本进程校验；
        // /actuator/** 同样不属于业务流量，直接放行
        if (path.startsWith(UrlConstant.MANAGE_PREFIX) || path.startsWith(UrlConstant.ACTUATOR_PREFIX)) {
            return chain.filter(exchange);
        }

        String authorization = exchange.getRequest().getHeaders().getFirst("Authorization");
        if (authorization == null || authorization.isBlank()) {
            // 最常见的 401，此前完全静默：接入方漏带 Authorization 时后端一条日志都没有，
            // 只能靠对方描述现象来猜。业务拒绝按 03 §7 记 INFO
            log.info("[CredentialAuth] 拒绝: 未携带 Authorization, path={}, clientIp={}",
                    path, clientIp(exchange));
            return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.MISSING_CREDENTIAL);
        }

        // DB 查询必须切换到 boundedElastic 线程池，避免在 Netty 事件循环线程中执行阻塞 IO
        return Mono.fromCallable(() -> authenticate(authorization))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(result -> {
                    if (result.isEmpty()) {
                        log.info("[CredentialAuth] 拒绝: 凭证无效或不存在, path={}, clientIp={}", path, clientIp(exchange));
                        return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_API_KEY);
                    }
                    AppCredentialEntity cred = result.get();

                    // 凭证状态检查（enabled=false 或已过期）
                    if (Boolean.FALSE.equals(cred.getEnabled())) {
                        log.info("[CredentialAuth] 拒绝: 凭证已禁用, appId={}, credId={}, path={}",
                                cred.getAppId(), cred.getId(), path);
                        return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.INVALID_API_KEY);
                    }
                    if (cred.getExpiresAt() != null && cred.getExpiresAt().isBefore(LocalDateTime.now())) {
                        log.info("[CredentialAuth] 拒绝: 凭证已过期, appId={}, credId={}, expiresAt={}, path={}",
                                cred.getAppId(), cred.getId(), cred.getExpiresAt(), path);
                        return reject(exchange, HttpStatus.UNAUTHORIZED, ErrorCode.CREDENTIAL_EXPIRED);
                    }

                    // 检查所属 App 是否启用（App 禁用 → 该 App 所有凭证立即失效）
                    Optional<AppInfoEntity> app;
                    try {
                        app = Optional.of(appService.findByAppId(cred.getAppId()));
                    } catch (Exception e) {
                        app = Optional.empty();
                    }
                    if (app.isEmpty() || Boolean.FALSE.equals(app.get().getEnabled())) {
                        // app.isEmpty() 既可能是系统被删、也可能是查库异常，两者后果相同但成因不同，
                        // 打出来才能区分"业务上禁用了"和"DB 出问题了"
                        log.info("[CredentialAuth] 拒绝: 接入系统不可用, appId={}, 存在={}, path={}",
                                cred.getAppId(), app.isPresent(), path);
                        return reject(exchange, HttpStatus.FORBIDDEN, ErrorCode.APP_DISABLED);
                    }

                    // 同时写 attribute（供先于本过滤器执行的 TraceFilter 在 doFinally 回读）
                    exchange.getAttributes().put(ATTR_APP_ID, cred.getAppId());
                    if (cred.getCredType() != null) {
                        exchange.getAttributes().put(ATTR_CRED_TYPE, cred.getCredType());
                    }
                    // 将 appId 和凭证类型注入请求头，供后续过滤器（ModelAuthFilter、QuotaFilter）读取
                    var mutated = exchange.getRequest().mutate()
                            .header("X-Mg-App", cred.getAppId())
                            .header("X-Mg-Cred-Type", cred.getCredType())
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                });
    }

    private Optional<AppCredentialEntity> authenticate(String authorization) {
        if (authorization.startsWith("Bearer ")) {
            // API Key 模式：对明文做 SHA-256，用哈希查凭证（DB 中不存明文）
            String apiKey = authorization.substring(7).trim();
            String hash = cryptoService.sha256Hex(apiKey);
            return credentialService.findBySecretHash(hash);
        }
        if (authorization.startsWith("AKSK ")) {
            // AK/SK 模式：格式 "AKSK {accessKey}:{timestamp毫秒}:{HMAC-SHA256(SK, accessKey+":"+timestamp) Base64}"
            String payload = authorization.substring(5).trim();
            String[] parts = payload.split(":", 3);
            if (parts.length != 3) return Optional.empty();
            String accessKey    = parts[0];
            String timestampStr = parts[1];
            String signature    = parts[2];

            // 1. 时间戳防重放：偏差超过 ±5 分钟直接拒绝
            long ts;
            try {
                ts = Long.parseLong(timestampStr);
            } catch (NumberFormatException e) {
                return Optional.empty();
            }
            if (Math.abs(System.currentTimeMillis() - ts) > MAX_TIMESTAMP_SKEW_MS) {
                log.info("[CredentialAuth] 拒绝: AKSK 时间戳超出 ±5 分钟窗口（防重放）, ts={}", ts);
                return Optional.empty();
            }

            // 2. 查询凭证
            Optional<AppCredentialEntity> credOpt = credentialService.findByAccessKey(accessKey);
            if (credOpt.isEmpty()) return Optional.empty();
            AppCredentialEntity cred = credOpt.get();

            // 3. 验证 HMAC-SHA256 签名：证明调用方持有 SK 而不仅仅知道 AK
            if (!verifyAkSkHmac(cred, accessKey, timestampStr, signature)) {
                log.info("[CredentialAuth] 拒绝: AKSK 签名校验失败, accessKey={}", accessKey);
                return Optional.empty();
            }
            return credOpt;
        }
        return Optional.empty();
    }

    /**
     * 验证 AKSK 签名。
     *
     * <p>签名算法：{@code HMAC-SHA256(plainSK, accessKey + ":" + timestamp)}，结果 Base64 编码。
     * 调用方需要用相同的 accessKey、timestamp 和 SK 计算签名并放入请求头。
     * 使用 {@link MessageDigest#isEqual} 恒时比较，防止时序攻击。
     */
    private boolean verifyAkSkHmac(AppCredentialEntity cred, String accessKey,
                                    String timestamp, String signature) {
        if (cred.getSecretEncrypted() == null) {
            log.warn("[CredentialAuth] AKSK 无密文 SK，无法验签（该凭证创建于密钥可查看启用之前，需重新生成）, accessKey={}", accessKey);
            return false;
        }
        try {
            String plainSk = cryptoService.decrypt(cred.getSecretEncrypted());
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(plainSk.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] expected = mac.doFinal((accessKey + ":" + timestamp).getBytes(StandardCharsets.UTF_8));
            byte[] actual;
            try {
                actual = Base64.getDecoder().decode(signature);
            } catch (IllegalArgumentException e) {
                return false; // signature is not valid Base64
            }
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            log.warn("[CredentialAuth] AKSK 验签异常（多为 MODELGATE_CRYPTO_KEY 变更导致密文解不开）, accessKey={}: {}", accessKey, e.getMessage());
            return false;
        }
    }

    /** 取调用方 IP 用于日志定位；取不到返回 unknown（不抛异常影响主流程）。 */
    private static String clientIp(ServerWebExchange exchange) {
        return exchange.getRequest().getRemoteAddress() != null
                ? exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "unknown";
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
