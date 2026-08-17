package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.pab.ficc.ibp.domain.base.vo.PageVo;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AppQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.CreateAppRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpdateAppRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppVO;
import com.pab.ficc.ibp.modelgate.server.service.AppService;
import com.pab.ficc.ibp.modelgate.server.service.CredentialService;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.validation.Valid;

import java.util.Map;

@Tag(name = "接入系统管理", description = "管理需要接入 ModelGate 的内部业务系统")
@RestController
@RequestMapping(UrlConstant.MG_APPS)
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;
    private final ModelAuthService modelAuthService;
    private final CredentialService credentialService;

    @Operation(summary = "分页查询接入系统（pageIndex 从 1 开始，pageSize 上限 200；keyword 模糊匹配 appId/appName/sysCode）")
    @GetMapping(UrlConstant.ROOT)
    public Mono<Result<PageVo<AppVO>>> page(AppQueryBO query) {
        return Mono.fromCallable(() -> {
                    // 授权数与凭证数各一次查询后内存聚合，避免逐行 N+1
                    Map<String, Long> authCounts = modelAuthService.countByAppId();
                    Map<String, Long> credCounts = credentialService.countByAppId();
                    return PageVo.of(appService.page(query),
                            a -> AppVO.of(a, authCounts.get(a.getAppId()), credCounts.get(a.getAppId())));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "查询单个接入系统详情")
    @GetMapping(UrlConstant.DETAIL)
    public Mono<Result<AppVO>> findOne(@Parameter(description = "系统 ID") @RequestParam("appId") String appId) {
        return Mono.fromCallable(() -> AppVO.of(appService.findByAppId(appId)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "注册接入系统")
    @PostMapping
    public Mono<Result<AppVO>> create(@RequestBody @Valid CreateAppRequestBO req) {
        return Mono.fromCallable(() -> AppVO.of(appService.create(req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "更新接入系统信息")
    @PutMapping(UrlConstant.UPDATE)
    public Mono<Result<AppVO>> update(@RequestBody @Valid UpdateAppRequestBO req) {
        return Mono.fromCallable(() -> AppVO.of(appService.update(req.getAppId(), req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "启用接入系统")
    @PatchMapping(UrlConstant.ENABLE)
    public Mono<Result<Void>> enable(@Parameter(description = "系统 ID") @RequestParam("appId") String appId) {
        return Mono.fromRunnable(() -> appService.enable(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "禁用接入系统（该系统所有凭证立即失效）")
    @PatchMapping(UrlConstant.DISABLE)
    public Mono<Result<Void>> disable(@Parameter(description = "系统 ID") @RequestParam("appId") String appId) {
        return Mono.fromRunnable(() -> appService.disable(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "删除接入系统（级联删除凭证、授权、访问规则）")
    @DeleteMapping(UrlConstant.DELETE)
    public Mono<Result<Void>> delete(@Parameter(description = "系统 ID") @RequestParam("appId") String appId) {
        return Mono.fromRunnable(() -> appService.delete(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }
}
