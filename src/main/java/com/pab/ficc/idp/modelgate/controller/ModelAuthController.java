package com.pab.ficc.idp.modelgate.controller;

import com.pab.ficc.idp.modelgate.common.Result;
import com.pab.ficc.idp.modelgate.entity.AppModelAuth;
import com.pab.ficc.idp.modelgate.service.ModelAuthService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin/apps/{appId}/auth")
@RequiredArgsConstructor
public class ModelAuthController {

    private final ModelAuthService modelAuthService;

    @GetMapping
    public Mono<Result<List<AppModelAuth>>> list(@PathVariable String appId) {
        return Mono.fromCallable(() -> modelAuthService.listByAppId(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping
    public Mono<Result<Void>> addAuthorizations(@PathVariable String appId,
                                                 @RequestBody @Valid AddAuthRequest req) {
        return Mono.fromRunnable(() -> modelAuthService.addAuthorizations(appId, req.getModelIds(), req.toTemplate()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PutMapping("/{modelId}")
    public Mono<Result<AppModelAuth>> update(@PathVariable String appId,
                                              @PathVariable String modelId,
                                              @RequestBody @Valid UpdateAuthRequest req) {
        return Mono.fromCallable(() -> modelAuthService.update(appId, modelId, req.toEntity()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PatchMapping("/{modelId}/enable")
    public Mono<Result<Void>> enable(@PathVariable String appId, @PathVariable String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.enable(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PatchMapping("/{modelId}/disable")
    public Mono<Result<Void>> disable(@PathVariable String appId, @PathVariable String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.disable(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @DeleteMapping("/{modelId}")
    public Mono<Result<Void>> revoke(@PathVariable String appId, @PathVariable String modelId) {
        return Mono.fromRunnable(() -> modelAuthService.revoke(appId, modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    // ===== DTOs =====

    @Data
    public static class AddAuthRequest {
        @NotEmpty private List<String> modelIds;
        private Integer qpsLimit = 0;
        private Integer dailyLimit = 0;
        private Long monthlyTokens = 0L;
        private LocalDateTime expiresAt;

        AppModelAuth toTemplate() {
            var a = new AppModelAuth();
            a.setQpsLimit(qpsLimit != null ? qpsLimit : 0);
            a.setDailyLimit(dailyLimit != null ? dailyLimit : 0);
            a.setMonthlyTokens(monthlyTokens != null ? monthlyTokens : 0L);
            a.setExpiresAt(expiresAt);
            return a;
        }
    }

    @Data
    public static class UpdateAuthRequest {
        private Integer qpsLimit = 0;
        private Integer dailyLimit = 0;
        private Long monthlyTokens = 0L;
        private LocalDateTime expiresAt;

        AppModelAuth toEntity() {
            var a = new AppModelAuth();
            a.setQpsLimit(qpsLimit != null ? qpsLimit : 0);
            a.setDailyLimit(dailyLimit != null ? dailyLimit : 0);
            a.setMonthlyTokens(monthlyTokens != null ? monthlyTokens : 0L);
            a.setExpiresAt(expiresAt);
            return a;
        }
    }
}
