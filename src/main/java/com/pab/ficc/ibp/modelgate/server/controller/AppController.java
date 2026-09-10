package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.pab.ficc.ibp.domain.base.vo.PageVo;
import com.pab.ficc.ibp.modelgate.server.common.AdminScheduler;
import com.pab.ficc.ibp.modelgate.server.common.PageSupport;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AppQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.CreateAppRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpdateAppRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppVO;
import com.pab.ficc.ibp.modelgate.server.service.AppService;
import com.pab.ficc.ibp.modelgate.server.service.CredentialService;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import com.pab.ficc.ibp.modelgate.server.service.ServiceAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;

import java.util.Map;

@Tag(name = "接入系统管理", description = "管理需要接入 ModelGate 的内部业务系统")
@RestController
@RequestMapping(UrlConstant.MG_APPS)
@RequiredArgsConstructor
public class AppController {

    private final AppService appService;
    private final ModelAuthService modelAuthService;
    private final ServiceAuthService serviceAuthService;
    private final CredentialService credentialService;

    @Operation(summary = "分页查询接入系统（pageIndex 从 1 开始，pageSize 上限 200；keyword 模糊匹配 appId/systemName/appName/sysCode）")
    @GetMapping(UrlConstant.PAGE)
    public Mono<Result<PageVo<AppVO>>> page(AppQueryBO query) {
        PageSupport.validate(query, "接入系统");
        return Mono.fromCallable(() -> {
                    // 授权数与凭证数各一次查询后内存聚合，避免逐行 N+1
                    Map<String, Long> authCounts = modelAuthService.countByAppId();
                    Map<String, Long> credCounts = credentialService.countByAppId();
                    // 服务授权数同样一次查询后内存聚合；模型授权与服务授权是两套独立的表，
                    // 分开展示才知道该去哪个子面板改
                    Map<String, Long> svcAuthCounts = serviceAuthService.countByAppId();
                    return PageVo.of(appService.page(query),
                            a -> AppVO.of(a, authCounts.get(a.getAppId()), credCounts.get(a.getAppId()),
                                    svcAuthCounts.get(a.getAppId())));
                })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "查询单个接入系统详情")
    @GetMapping(UrlConstant.DETAIL)
    public Mono<Result<AppVO>> findOne(@Parameter(description = "系统 ID") @RequestParam("appId") String appId) {
        return Mono.fromCallable(() -> AppVO.of(appService.findByAppId(appId)))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "注册接入系统")
    @PostMapping(UrlConstant.CREATE)
    public Mono<Result<AppVO>> create(@RequestBody @Valid CreateAppRequestBO req) {
        return Mono.fromCallable(() -> AppVO.of(appService.create(req.toEntity())))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "更新接入系统信息")
    @PutMapping(UrlConstant.UPDATE)
    public Mono<Result<AppVO>> update(@RequestBody @Valid UpdateAppRequestBO req) {
        return Mono.fromCallable(() -> AppVO.of(appService.update(req.getAppId(), req.toEntity())))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "启用/禁用接入系统（enabled=false 时禁用，该系统所有凭证立即失效）")
    @PatchMapping(UrlConstant.TOGGLE)
    public Mono<Result<Void>> toggle(
            @Parameter(description = "系统 ID") @RequestParam("appId") String appId,
            @Parameter(description = "目标状态：true=启用，false=禁用") @RequestParam("enabled") boolean enabled) {
        return Mono.fromRunnable(() -> {
                    if (enabled) appService.enable(appId);
                    else appService.disable(appId);
                })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "删除接入系统（级联删除凭证、授权、访问规则）")
    @DeleteMapping(UrlConstant.DELETE)
    public Mono<Result<Void>> delete(@Parameter(description = "系统 ID") @RequestParam("appId") String appId) {
        return Mono.fromRunnable(() -> appService.delete(appId))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }
}
