package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.AddAuthRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateAuthRequest;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppModelAuthVO;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "模型授权", description = "配置接入系统可访问的模型范围及 QPS / 配额限制")
@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class ModelAuthController {

    private final ModelAuthService modelAuthService;

    @Operation(summary = "查询系统的模型授权列表")
    @GetMapping("/list")
    public Mono<Result<List<AppModelAuthVO>>> list(
            @Parameter(description = "系统 ID") @RequestParam String appId) {
        return Mono.fromCallable(() -> modelAuthService.listByAppId(appId)
                        .stream().map(AppModelAuthVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @Operation(summary = "为系统批量授权模型")
    @PostMapping("/add")
    public Mono<Result<Void>> addAuthorizations(@RequestBody @Valid AddAuthRequest req) {
        return Mono.fromRunnable(() -> modelAuthService.addAuthorizations(
                        req.getAppId(), req.getModelIds(), req.toTemplate()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @Operation(summary = "更新授权配置（QPS / 日限 / 月 Token 配额）")
    @PutMapping("/update")
    public Mono<Result<AppModelAuthVO>> update(@RequestBody @Valid UpdateAuthRequest req) {
        return Mono.fromCallable(() -> AppModelAuthVO.of(
                        modelAuthService.update(req.getAppId(), req.getModelId(), req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @Operation(summary = "启用单个模型授权")
    @PatchMapping("/enable")
    public Mono<Result<Void>> enable(
            @Parameter(description = "系统 ID") @RequestParam String appId,
            @Parameter(description = "模型 ID") @RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.enable(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @Operation(summary = "禁用单个模型授权")
    @PatchMapping("/disable")
    public Mono<Result<Void>> disable(
            @Parameter(description = "系统 ID") @RequestParam String appId,
            @Parameter(description = "模型 ID") @RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.disable(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @Operation(summary = "撤销模型授权")
    @DeleteMapping("/revoke")
    public Mono<Result<Void>> revoke(
            @Parameter(description = "系统 ID") @RequestParam String appId,
            @Parameter(description = "模型 ID") @RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.revoke(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }
}
