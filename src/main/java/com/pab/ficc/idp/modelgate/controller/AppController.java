package com.pab.ficc.idp.modelgate.controller;

import com.pab.ficc.idp.modelgate.common.Result;
import com.pab.ficc.idp.modelgate.entity.AppInfo;
import com.pab.ficc.idp.modelgate.service.AppService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/admin/apps")
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;

    @GetMapping
    public Mono<Result<List<AppInfo>>> findAll() {
        return Mono.fromCallable(appService::findAll)
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @GetMapping("/{appId}")
    public Mono<Result<AppInfo>> findOne(@PathVariable String appId) {
        return Mono.fromCallable(() -> appService.findByAppId(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping
    public Mono<Result<AppInfo>> create(@RequestBody @Valid CreateAppRequest req) {
        return Mono.fromCallable(() -> appService.create(req.toEntity()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PutMapping("/{appId}")
    public Mono<Result<AppInfo>> update(@PathVariable String appId,
                                        @RequestBody @Valid UpdateAppRequest req) {
        return Mono.fromCallable(() -> appService.update(appId, req.toEntity()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PatchMapping("/{appId}/enable")
    public Mono<Result<Void>> enable(@PathVariable String appId) {
        return Mono.fromRunnable(() -> appService.enable(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PatchMapping("/{appId}/disable")
    public Mono<Result<Void>> disable(@PathVariable String appId) {
        return Mono.fromRunnable(() -> appService.disable(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @DeleteMapping("/{appId}")
    public Mono<Result<Void>> delete(@PathVariable String appId) {
        return Mono.fromRunnable(() -> appService.delete(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    // ===== DTOs =====

    @Data
    public static class CreateAppRequest {
        @NotBlank private String appId;
        @NotBlank private String appName;
        @NotBlank private String sysCode;
        @NotBlank private String appCode;
        @NotBlank private String owner;
        private String remark;

        AppInfo toEntity() {
            var a = new AppInfo();
            a.setAppId(appId);
            a.setAppName(appName);
            a.setSysCode(sysCode);
            a.setAppCode(appCode);
            a.setOwner(owner);
            a.setRemark(remark);
            a.setEnabled(true);
            return a;
        }
    }

    @Data
    public static class UpdateAppRequest {
        @NotBlank private String appName;
        @NotBlank private String sysCode;
        @NotBlank private String appCode;
        @NotBlank private String owner;
        private String remark;

        AppInfo toEntity() {
            var a = new AppInfo();
            a.setAppName(appName);
            a.setSysCode(sysCode);
            a.setAppCode(appCode);
            a.setOwner(owner);
            a.setRemark(remark);
            return a;
        }
    }
}
