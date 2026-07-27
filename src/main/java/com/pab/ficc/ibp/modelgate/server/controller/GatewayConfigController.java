package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.common.UrlConstant;

import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.cus.web.result.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpstreamUrlRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpsertConfigRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.GatewayConfigEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.GatewayConfigVO;
import com.pab.ficc.ibp.modelgate.server.service.GatewayConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 网关动态配置管理接口
 *
 * GET    /admin/config              — 查询所有配置
 * GET    /admin/config/detail       — 查询单个配置（?key=）
 * PUT    /admin/config/save         — 新增或更新配置（body 含 key）
 * DELETE /admin/config/delete       — 删除配置（?key=）
 * GET    /admin/config/upstream/url — 快捷查询上游地址
 * PUT    /admin/config/upstream/url — 快捷修改上游地址
 */
@Tag(name = "网关配置", description = "动态管理网关全局配置，变更实时生效")
@Slf4j
@RestController
@RequestMapping(UrlConstant.ADMIN_CONFIG)
@RequiredArgsConstructor
public class GatewayConfigController {

    private final GatewayConfigService configService;

    @Operation(summary = "查询所有网关配置项")
    @GetMapping
    public Mono<Result<List<GatewayConfigVO>>> findAll() {
        return Mono.fromCallable(() -> configService.findAll()
                        .stream().map(GatewayConfigVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "查询单个配置项")
    @GetMapping(UrlConstant.DETAIL)
    public Mono<Result<GatewayConfigVO>> findOne(
            @Parameter(description = "配置 key") @RequestParam String key) {
        return Mono.fromCallable(() -> configService.findAll().stream()
                        .filter(c -> c.getConfigKey().equals(key))
                        .findFirst()
                        .map(GatewayConfigVO::of)
                        .orElseThrow(() -> new BusinessException(ErrorCode.WHITELIST_NOT_FOUND, "config key: " + key)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "新增或更新配置项")
    @PutMapping(UrlConstant.SAVE)
    public Mono<Result<GatewayConfigVO>> upsert(@RequestBody @Valid UpsertConfigRequestBO req,
                                                 ServerHttpRequest httpRequest) {
        String operator = httpRequest.getHeaders().getFirst("X-Admin-User");
        GatewayConfigEntity config = GatewayConfigEntity.builder()
                .configKey(req.getKey())
                .configValue(req.getValue())
                .description(req.getDescription())
                .build();

        return Mono.fromCallable(() -> GatewayConfigVO.of(
                        configService.upsert(config, operator != null ? operator : "unknown")))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "删除配置项")
    @DeleteMapping(UrlConstant.DELETE)
    public Mono<Result<Void>> delete(
            @Parameter(description = "配置 key") @RequestParam String key) {
        return Mono.fromRunnable(() -> configService.delete(key))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "查询当前上游模型服务地址")
    @GetMapping(UrlConstant.UPSTREAM_URL)
    public Mono<Result<String>> getUpstreamUrl() {
        return Mono.just(Result.success(configService.getUpstreamUrl()));
    }

    @Operation(summary = "修改上游模型服务地址（30s 内热生效）")
    @PutMapping(UrlConstant.UPSTREAM_URL)
    public Mono<Result<GatewayConfigVO>> updateUpstreamUrl(@RequestBody @Valid UpstreamUrlRequestBO req,
                                                            ServerHttpRequest httpRequest) {
        String operator = httpRequest.getHeaders().getFirst("X-Admin-User");
        GatewayConfigEntity config = GatewayConfigEntity.builder()
                .configKey(GatewayConfigEntity.KEY_UPSTREAM_URL)
                .configValue(req.getUrl())
                .description("上游模型服务地址")
                .build();

        return Mono.fromCallable(() -> GatewayConfigVO.of(
                        configService.upsert(config, operator != null ? operator : "unknown")))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(vo -> log.info("[Config] upstream.url updated to: {} by {}", req.getUrl(), operator))
                .map(Result::success);
    }
}
