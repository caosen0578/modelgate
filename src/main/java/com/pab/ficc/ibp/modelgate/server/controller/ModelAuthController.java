package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AddAuthRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpdateAuthRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppModelAuthVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ModelAuthorizedAppVO;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.validation.Valid;
import java.util.List;

@Tag(name = "模型授权", description = "配置接入系统可访问的模型范围及 QPS / 配额限制")
@RestController
@RequestMapping(UrlConstant.MG_AUTH)
@RequiredArgsConstructor
public class ModelAuthController {

    private final ModelAuthService modelAuthService;

    @Operation(summary = "查询系统的模型授权列表")
    @GetMapping(UrlConstant.LIST)
    public Mono<Result<List<AppModelAuthVO>>> list(
            @Parameter(description = "系统 ID") @RequestParam("appId") String appId) {
        return Mono.fromCallable(() -> modelAuthService.listByAppIdWithModelName(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "反查：某个模型被哪些接入系统授权（含负责人与是否实际生效）")
    @GetMapping(UrlConstant.APPS)
    public Mono<Result<List<ModelAuthorizedAppVO>>> listAuthorizedApps(
            @Parameter(description = "模型 ID") @RequestParam(name = "modelId") String modelId) {
        return Mono.fromCallable(() -> modelAuthService.listAuthorizedApps(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "为系统批量授权模型")
    @PostMapping(UrlConstant.ADD)
    public Mono<Result<Void>> addAuthorizations(@RequestBody @Valid AddAuthRequestBO req) {
        return Mono.fromRunnable(() -> modelAuthService.addAuthorizations(
                        req.getAppId(), req.getModelIds(), req.toTemplate()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "更新授权配置（QPS / 日限 / 月 Token 配额）")
    @PutMapping(UrlConstant.UPDATE)
    public Mono<Result<AppModelAuthVO>> update(@RequestBody @Valid UpdateAuthRequestBO req) {
        return Mono.fromCallable(() -> AppModelAuthVO.of(
                        modelAuthService.update(req.getAppId(), req.getModelId(), req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "启用单个模型授权")
    @PatchMapping(UrlConstant.ENABLE)
    public Mono<Result<Void>> enable(
            @Parameter(description = "系统 ID") @RequestParam(name = "appId") String appId,
            @Parameter(description = "模型 ID") @RequestParam(name = "modelId") String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.enable(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "禁用单个模型授权")
    @PatchMapping(UrlConstant.DISABLE)
    public Mono<Result<Void>> disable(
            @Parameter(description = "系统 ID") @RequestParam("appId") String appId,
            @Parameter(description = "模型 ID") @RequestParam("modelId") String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.disable(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "撤销模型授权")
    @DeleteMapping(UrlConstant.REVOKE)
    public Mono<Result<Void>> revoke(
            @Parameter(description = "系统 ID") @RequestParam("appId") String appId,
            @Parameter(description = "模型 ID") @RequestParam("modelId") String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.revoke(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }
}
