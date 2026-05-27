package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpstreamUrlRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpsertConfigRequest;
import com.pab.ficc.ibp.modelgate.server.domain.entity.GatewayConfig;
import com.pab.ficc.ibp.modelgate.server.domain.vo.GatewayConfigVO;
import com.pab.ficc.ibp.modelgate.server.service.GatewayConfigService;
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
@Slf4j
@RestController
@RequestMapping("/admin/config")
@RequiredArgsConstructor
public class GatewayConfigController {

    private final GatewayConfigService configService;

    @GetMapping
    public Mono<Result<List<GatewayConfigVO>>> findAll() {
        return Mono.fromCallable(() -> configService.findAll()
                        .stream().map(GatewayConfigVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @GetMapping("/detail")
    public Mono<Result<GatewayConfigVO>> findOne(@RequestParam String key) {
        return Mono.fromCallable(() -> configService.findAll().stream()
                        .filter(c -> c.getConfigKey().equals(key))
                        .findFirst()
                        .map(GatewayConfigVO::of)
                        .orElseThrow(() -> new BusinessException(ErrorCode.WHITELIST_NOT_FOUND, "config key: " + key)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PutMapping("/save")
    public Mono<Result<GatewayConfigVO>> upsert(@RequestBody @Valid UpsertConfigRequest req,
                                                 ServerHttpRequest httpRequest) {
        String operator = httpRequest.getHeaders().getFirst("X-Admin-User");
        GatewayConfig config = GatewayConfig.builder()
                .configKey(req.getKey())
                .configValue(req.getValue())
                .description(req.getDescription())
                .build();

        return Mono.fromCallable(() -> GatewayConfigVO.of(
                        configService.upsert(config, operator != null ? operator : "unknown")))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @DeleteMapping("/delete")
    public Mono<Result<Void>> delete(@RequestParam String key) {
        return Mono.fromRunnable(() -> configService.delete(key))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @GetMapping("/upstream/url")
    public Mono<Result<String>> getUpstreamUrl() {
        return Mono.just(Result.ok(configService.getUpstreamUrl()));
    }

    @PutMapping("/upstream/url")
    public Mono<Result<GatewayConfigVO>> updateUpstreamUrl(@RequestBody @Valid UpstreamUrlRequest req,
                                                            ServerHttpRequest httpRequest) {
        String operator = httpRequest.getHeaders().getFirst("X-Admin-User");
        GatewayConfig config = GatewayConfig.builder()
                .configKey(GatewayConfig.KEY_UPSTREAM_URL)
                .configValue(req.getUrl())
                .description("上游模型服务地址")
                .build();

        return Mono.fromCallable(() -> GatewayConfigVO.of(
                        configService.upsert(config, operator != null ? operator : "unknown")))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(vo -> log.info("[Config] upstream.url updated to: {} by {}", req.getUrl(), operator))
                .map(Result::ok);
    }
}
