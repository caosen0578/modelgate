package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.AddAuthRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateAuthRequest;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppModelAuthVO;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class ModelAuthController {

    private final ModelAuthService modelAuthService;

    @GetMapping("/list")
    public Mono<Result<List<AppModelAuthVO>>> list(@RequestParam String appId) {
        return Mono.fromCallable(() -> modelAuthService.listByAppId(appId)
                        .stream().map(AppModelAuthVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping("/add")
    public Mono<Result<Void>> addAuthorizations(@RequestBody @Valid AddAuthRequest req) {
        return Mono.fromRunnable(() -> modelAuthService.addAuthorizations(
                        req.getAppId(), req.getModelIds(), req.toTemplate()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PutMapping("/update")
    public Mono<Result<AppModelAuthVO>> update(@RequestBody @Valid UpdateAuthRequest req) {
        return Mono.fromCallable(() -> AppModelAuthVO.of(
                        modelAuthService.update(req.getAppId(), req.getModelId(), req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PatchMapping("/enable")
    public Mono<Result<Void>> enable(@RequestParam String appId, @RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.enable(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PatchMapping("/disable")
    public Mono<Result<Void>> disable(@RequestParam String appId, @RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.disable(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @DeleteMapping("/revoke")
    public Mono<Result<Void>> revoke(@RequestParam String appId, @RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.revoke(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }
}
