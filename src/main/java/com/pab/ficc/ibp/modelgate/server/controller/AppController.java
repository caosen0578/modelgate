package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.CreateAppRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateAppRequest;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppVO;
import com.pab.ficc.ibp.modelgate.server.service.AppService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/apps")
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;

    @GetMapping
    public Mono<Result<List<AppVO>>> findAll() {
        return Mono.fromCallable(() -> appService.findAll()
                        .stream().map(AppVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @GetMapping("/detail")
    public Mono<Result<AppVO>> findOne(@RequestParam String appId) {
        return Mono.fromCallable(() -> AppVO.of(appService.findByAppId(appId)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping
    public Mono<Result<AppVO>> create(@RequestBody @Valid CreateAppRequest req) {
        return Mono.fromCallable(() -> AppVO.of(appService.create(req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PutMapping("/update")
    public Mono<Result<AppVO>> update(@RequestBody @Valid UpdateAppRequest req) {
        return Mono.fromCallable(() -> AppVO.of(appService.update(req.getAppId(), req.toEntity())))
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
}
