package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuth;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/admin/auth")
@RequiredArgsConstructor
public class ModelAuthController {

    private final ModelAuthService modelAuthService;

    @GetMapping("/list")
    public Mono<Result<List<AppModelAuth>>> list(@RequestParam String appId) {
        return Mono.fromCallable(() -> modelAuthService.listByAppId(appId))
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
    public Mono<Result<AppModelAuth>> update(@RequestBody @Valid UpdateAuthRequest req) {
        return Mono.fromCallable(() -> modelAuthService.update(
                        req.getAppId(), req.getModelId(), req.toEntity()))
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

    // ===== DTOs =====

    @Data
    public static class AddAuthRequest {
        @NotBlank private String appId;
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
        @NotBlank private String appId;
        @NotBlank private String modelId;
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
