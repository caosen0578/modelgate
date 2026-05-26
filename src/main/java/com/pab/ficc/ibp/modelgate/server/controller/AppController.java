package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfo;
import com.pab.ficc.ibp.modelgate.server.service.AppService;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
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

    @GetMapping("/detail")
    public Mono<Result<AppInfo>> findOne(@RequestParam String appId) {
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

    @PutMapping("/update")
    public Mono<Result<AppInfo>> update(@RequestBody @Valid UpdateAppRequest req) {
        return Mono.fromCallable(() -> appService.update(req.getAppId(), req.toEntity()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PatchMapping("/enable")
    public Mono<Result<Void>> enable(@RequestParam String appId) {
        return Mono.fromRunnable(() -> appService.enable(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PatchMapping("/disable")
    public Mono<Result<Void>> disable(@RequestParam String appId) {
        return Mono.fromRunnable(() -> appService.disable(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @DeleteMapping("/delete")
    public Mono<Result<Void>> delete(@RequestParam String appId) {
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
        @NotBlank private String appId;
        @NotBlank private String appName;
        @NotBlank private String sysCode;
        @NotBlank private String appCode;
        @NotBlank private String owner;
        private String remark;
        private Boolean enabled;

        AppInfo toEntity() {
            var a = new AppInfo();
            a.setAppName(appName);
            a.setSysCode(sysCode);
            a.setAppCode(appCode);
            a.setOwner(owner);
            a.setRemark(remark);
            if (enabled != null) a.setEnabled(enabled);
            return a;
        }
    }
}
