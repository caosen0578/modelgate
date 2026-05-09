package com.pab.ficc.idp.modelgate.controller;

import com.pab.ficc.idp.modelgate.common.BusinessException;
import com.pab.ficc.idp.modelgate.common.ErrorCode;
import com.pab.ficc.idp.modelgate.common.Result;
import com.pab.ficc.idp.modelgate.entity.GatewayConfig;
import com.pab.ficc.idp.modelgate.service.GatewayConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 网关动态配置管理接口
 * <p>
 * 由 AdminAuthFilter 保护，需要 X-Admin-Token 或 Basic Auth。
 * <p>
 * GET    /admin/config              — 查询所有配置
 * GET    /admin/config/{key}        — 查询单个配置
 * PUT    /admin/config/{key}        — 新增或更新配置
 * DELETE /admin/config/{key}        — 删除配置
 * GET    /admin/config/upstream/url — 快捷查询当前上游地址
 * PUT    /admin/config/upstream/url — 快捷修改上游地址（最常用）
 */
@Slf4j
@RestController
@RequestMapping("/admin/config")
@RequiredArgsConstructor
public class GatewayConfigController {

    private final GatewayConfigService configService;

    // ===== 查询全部 =====

    @GetMapping
    public Mono<Result<List<GatewayConfig>>> findAll() {
        return Mono.fromCallable(configService::findAll)
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    // ===== 查询单个 =====

    @GetMapping("/{key}")
    public Mono<Result<GatewayConfig>> findOne(@PathVariable String key) {
        return Mono.fromCallable(() -> configService.findAll().stream()
                        .filter(c -> c.getConfigKey().equals(key))
                        .findFirst()
                        .orElseThrow(() -> new BusinessException(ErrorCode.WHITELIST_NOT_FOUND, "config key: " + key)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    // ===== 新增或更新（UPSERT） =====

    @PutMapping("/{key}")
    public Mono<Result<GatewayConfig>> upsert(@PathVariable String key,
                                               @RequestBody @Valid UpsertRequest req,
                                               ServerHttpRequest httpRequest) {
        String operator = httpRequest.getHeaders().getFirst("X-Admin-User");
        GatewayConfig config = GatewayConfig.builder()
                .configKey(key)
                .configValue(req.getValue())
                .description(req.getDescription())
                .build();

        return Mono.fromCallable(() -> configService.upsert(config, operator != null ? operator : "unknown"))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    // ===== 删除 =====

    @DeleteMapping("/{key}")
    public Mono<Result<Void>> delete(@PathVariable String key) {
        return Mono.fromRunnable(() -> configService.delete(key))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    // ===== 快捷接口：上游地址 =====

    @GetMapping("/upstream/url")
    public Mono<Result<String>> getUpstreamUrl() {
        return Mono.just(Result.ok(configService.getUpstreamUrl()));
    }

    @PutMapping("/upstream/url")
    public Mono<Result<GatewayConfig>> updateUpstreamUrl(@RequestBody @Valid UpstreamUrlRequest req,
                                                          ServerHttpRequest httpRequest) {
        String operator = httpRequest.getHeaders().getFirst("X-Admin-User");
        GatewayConfig config = GatewayConfig.builder()
                .configKey(GatewayConfig.KEY_UPSTREAM_URL)
                .configValue(req.getUrl())
                .description("上游模型服务地址")
                .build();

        return Mono.fromCallable(() -> configService.upsert(config, operator != null ? operator : "unknown"))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(c -> log.info("[Config] upstream.url updated to: {} by {}", req.getUrl(), operator))
                .map(Result::ok);
    }

    // ===== DTO =====

    @Data
    public static class UpsertRequest {
        @NotBlank(message = "value 不能为空")
        private String value;
        private String description;
    }

    @Data
    public static class UpstreamUrlRequest {
        @NotBlank(message = "url 不能为空")
        private String url;
    }
}
