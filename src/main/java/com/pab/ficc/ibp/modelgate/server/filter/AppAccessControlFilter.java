package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.common.TrafficType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.admin.IpMatcher;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRuleEntity;
import com.pab.ficc.ibp.modelgate.server.service.AccessRuleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.List;

/**
 * IP / 域名访问控制过滤器（order=-180）。
 *
 * <p>读取 CredentialAuthFilter 注入的 X-Mg-App，从缓存加载该 App 的访问规则，
 * 校验客户端来源是否在白名单内。规则列表为空时放行所有来源。
 *
 * <p>支持四种规则类型（rule_type）：
 * <ul>
 *   <li>ip             — 精确 IP，适合办公区固定 IP，如 192.168.1.5</li>
 *   <li>cidr           — IP 网段，适合 K8s Pod 网段，如 10.244.0.0/16</li>
 *   <li>domain         — 精确域名，匹配 Origin 请求头，如 crm.internal.com</li>
 *   <li>domain_wildcard— 通配域名，匹配 Origin 请求头，如 *.internal.com</li>
 * </ul>
 *
 * <p>K8s 最佳实践：
 * <ul>
 *   <li>K8s 内部服务（Pod IP 动态变化）→ 配置 Pod CIDR 规则（如 10.244.0.0/16）</li>
 *   <li>办公区用户（固定 IP 或子网）→ 配置 ip 或 cidr 规则</li>
 *   <li>浏览器前端（携带 Origin 头）→ 配置 domain / domain_wildcard 规则</li>
 *   <li>完全内部信任场景 → 规则留空，放行所有来源</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AppAccessControlFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -180;

    private final AccessRuleService accessRuleService;
    private final IpMatcher ipMatcher;
    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 通用能力：模型流量与服务流量都要走，只跳过管理面/运维端点。
        // 流量类型由 TraceFilter(-200) 算一次存 attribute，这里只读不算。
        if (!TrafficType.resolve(exchange).isBusiness()) {
            return chain.filter(exchange);
        }

        String appId = exchange.getRequest().getHeaders().getFirst("X-Mg-App");
        if (appId == null) {
            return chain.filter(exchange);
        }

        String clientIp     = getClientIp(exchange.getRequest());
        String clientDomain = getClientDomain(exchange.getRequest());

        return Mono.fromCallable(() -> accessRuleService.listByAppIdCached(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(rules -> {
                    if (rules.isEmpty()) {
                        // 无规则 = 不限制来源。适合 K8s 动态 IP 且不需要来源校验的场景。
                        return chain.filter(exchange);
                    }
                    if (isAllowed(clientIp, clientDomain, rules)) {
                        return chain.filter(exchange);
                    }
                    log.info("[AccessControl] 拒绝: 来源不在白名单, appId={}, clientIp={}, domain={}", appId, clientIp, clientDomain);
                    return reject(exchange, HttpStatus.FORBIDDEN, ErrorCode.ACCESS_DENIED);
                });
    }

    /**
     * 任意一条规则命中即返回 true（OR 逻辑）。
     */
    private boolean isAllowed(String clientIp, String clientDomain, List<AccessRuleEntity> rules) {
        for (AccessRuleEntity rule : rules) {
            switch (rule.getRuleType()) {
                case "ip"              -> { if (clientIp != null && clientIp.equals(rule.getRuleValue())) return true; }
                case "cidr"            -> { if (clientIp != null && ipMatcher.matchesCidr(clientIp, rule.getRuleValue())) return true; }
                case "domain"          -> { if (ipMatcher.matchesDomain(clientDomain, rule.getRuleValue())) return true; }
                case "domain_wildcard" -> { if (ipMatcher.matchesDomainWildcard(clientDomain, rule.getRuleValue())) return true; }
            }
        }
        return false;
    }

    /**
     * 提取客户端真实 IP。
     *
     * <p>取 X-Forwarded-For 的<b>最后一个</b>值：攻击者可以伪造靠前的值（如 10.0.0.1, 1.2.3.4），
     * 但可信代理（Nginx/K8s Ingress）只会在末尾追加真实来源 IP，因此最后一个值不可伪造。
     * 若无 XFF，退回 X-Real-IP（由代理注入），最后回退到 TCP 连接的 remoteAddress。
     */
    private String getClientIp(ServerHttpRequest request) {
        String xff = request.getHeaders().getFirst("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) {
            String[] parts = xff.split(",");
            return parts[parts.length - 1].trim();
        }
        String xri = request.getHeaders().getFirst("X-Real-IP");
        if (xri != null && !xri.isBlank()) return xri;
        if (request.getRemoteAddress() == null) return null;
        return request.getRemoteAddress().getAddress().getHostAddress();
    }

    /**
     * 从 Origin 或 Referer 请求头提取客户端域名（仅用于 domain 规则）。
     * 浏览器会携带 Origin 头，K8s 服务间调用通常没有，此时返回 null。
     */
    private String getClientDomain(ServerHttpRequest request) {
        String origin = request.getHeaders().getFirst("Origin");
        if (origin != null && !origin.isBlank()) {
            try { return URI.create(origin).getHost(); } catch (Exception ignored) {}
        }
        String referer = request.getHeaders().getFirst("Referer");
        if (referer != null && !referer.isBlank()) {
            try { return URI.create(referer).getHost(); } catch (Exception ignored) {}
        }
        return null;
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
