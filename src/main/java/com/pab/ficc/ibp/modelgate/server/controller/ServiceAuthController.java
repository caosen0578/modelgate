package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AddServiceAuthRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpdateServiceAuthRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ServiceAuthVO;
import com.pab.ficc.ibp.modelgate.server.service.ServiceAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 服务授权（接入系统 × 通用服务）。
 *
 * <p>与 {@code ModelAuthController} 平级、互不影响：凭证鉴权完全共用，
 * 真正决定"这个系统能不能调这个服务"的是 {@code mg_app_service_auth}。
 *
 * <p>DB 操作一律 {@code subscribeOn(boundedElastic)}，理由见 {@code ServiceController} 类注释。
 */
@Slf4j
@Tag(name = "服务授权", description = "配置接入系统可访问的服务范围及 QPS / 日调用量限制")
@RestController
@RequestMapping(UrlConstant.MG_SERVICE_AUTH)
@RequiredArgsConstructor
public class ServiceAuthController {

    private final ServiceAuthService serviceAuthService;

    @Operation(summary = "查询某接入系统的服务授权列表（含是否实际生效）")
    @GetMapping(UrlConstant.LIST)
    public Mono<Result<List<ServiceAuthVO>>> list(
            @Parameter(description = "接入系统 ID") @RequestParam("appId") String appId) {
        return Mono.fromCallable(() -> serviceAuthService.listByAppIdWithServiceName(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "为系统批量授权服务（已授权的跳过，不覆盖既有配额）")
    @PostMapping(UrlConstant.ADD)
    public Mono<Result<Void>> add(@RequestBody @Valid AddServiceAuthRequestBO req) {
        return Mono.fromRunnable(() -> serviceAuthService.addAuthorizations(
                        req.getAppId(), req.getServiceIds(), req.toTemplate()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "修改单条服务授权（配额/启停/过期时间；过期时间传空=改为永久）")
    @PostMapping(UrlConstant.UPDATE)
    public Mono<Result<Void>> update(@RequestBody @Valid UpdateServiceAuthRequestBO req) {
        return Mono.fromRunnable(() -> serviceAuthService.updateAuthorization(
                        req.getAppId(), req.getServiceId(), req.getEnabled(),
                        req.getQpsLimit(), req.getDailyLimit(), req.getExpiresAt()))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "撤销授权（逻辑删除，可重新授权）")
    @PostMapping(UrlConstant.REVOKE)
    public Mono<Result<Void>> revoke(
            @Parameter(description = "接入系统 ID") @RequestParam("appId") String appId,
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId) {
        return Mono.fromRunnable(() -> serviceAuthService.revoke(appId, serviceId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }
}
