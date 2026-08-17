package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpsertConfigRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.GatewayConfigEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.GatewayConfigVO;
import com.pab.ficc.ibp.modelgate.server.service.GatewayConfigService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 网关动态配置管理接口
 *
 * GET    /modelgate/config              — 查询所有配置
 * GET    /modelgate/config/detail       — 查询单个配置（?key=）
 * PUT    /modelgate/config/save         — 新增或更新配置（body 含 key）
 * DELETE /modelgate/config/delete       — 删除配置（?key=）
 */
@Tag(name = "网关配置", description = "动态管理网关全局配置，变更实时生效")
@RestController
@RequestMapping(UrlConstant.MG_CONFIG)
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
            @Parameter(description = "配置 key") @RequestParam("key") String key) {
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
            @Parameter(description = "配置 key") @RequestParam("key") String key) {
        return Mono.fromRunnable(() -> configService.delete(key))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    // 注：原 GET/PUT /modelgate/config/upstream/url 两个快捷接口已移除。
    // 它们操作的 upstream.url 配置项从未参与路由——真实转发地址由 ModelRouteFilter
    // 按请求体的 model 字段查 mg_model.upstream_url 动态覆盖。原接口的
    // "30s 内热生效" 描述具有误导性：改了不影响任何流量。
}
