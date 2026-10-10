package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.AdminScheduler;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AddServiceAuthRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpdateServiceAuthRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ServiceAuthVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ServiceAuthorizedAppVO;
import com.pab.ficc.ibp.modelgate.server.service.ServiceAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

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
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    /**
     * 反查：某个服务被哪些接入系统授权。
     *
     * <p><b>放在授权 Controller 上而不是 ServiceController</b>：模型侧的同名反查就是
     * {@code GET /auth/apps?modelId=}，挂在 ModelAuthController 上。
     * 这个动作查的是授权表、不是服务表，放这里语义也更顺。
     * 第一版曾放在 {@code /services/apps}，与模型侧不对称，已由
     * {@code ServiceMirrorsModelTest} 钉住。
     */
    @Operation(summary = "反查：某服务被哪些接入系统授权（含是否实际生效）")
    @GetMapping(UrlConstant.APPS)
    public Mono<Result<List<ServiceAuthorizedAppVO>>> listAuthorizedApps(
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId) {
        return Mono.fromCallable(() -> serviceAuthService.listAuthorizedApps(serviceId))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "为系统批量授权服务（已授权的跳过，不覆盖既有配额）")
    @PostMapping(UrlConstant.ADD)
    public Mono<Result<Void>> add(@RequestBody @Valid AddServiceAuthRequestBO req) {
        return Mono.fromRunnable(() -> serviceAuthService.addAuthorizations(
                        req.getAppId(), req.getServiceIds(), req.toTemplate()))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "修改单条服务授权（配额/过期时间；过期时间传空=改为永久）")
    @PutMapping(UrlConstant.UPDATE)
    public Mono<Result<ServiceAuthVO>> update(@RequestBody @Valid UpdateServiceAuthRequestBO req) {
        return Mono.fromCallable(() -> serviceAuthService.updateAuthorization(
                        req.getAppId(), req.getServiceId(), req.getEnabled(),
                        req.getQpsLimit(), req.getDailyLimit(), req.getExpiresAt()))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    /**
     * 启用/禁用单条服务授权。
     *
     * <p>与 update 分开是刻意的：列表上的启停是一次点击就该完成的动作，
     * 走 update 前端必须把 qps/daily/expiresAt 全带上，少带一个就会顺手清掉人家配好的值
     * （尤其 expiresAt 传 null 的语义是"改为永久"）。模型侧同样是独立的 PATCH。
     *
     * <p>底层 {@code setAuthEnabled} 本来就收一个布尔，此前拆成 enable/disable 两个接口
     * 只是在 Controller 层把这个布尔编进了 URL，2026-09-10 合并回来。
     */
    @Operation(summary = "启用/禁用单条服务授权（enabled=false 时禁用，禁用后该系统访问该服务返回 403）")
    @PatchMapping(UrlConstant.TOGGLE)
    public Mono<Result<Void>> toggle(
            @Parameter(description = "接入系统 ID") @RequestParam("appId") String appId,
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId,
            @Parameter(description = "目标状态：true=启用，false=禁用") @RequestParam("enabled") boolean enabled) {
        return Mono.fromRunnable(() -> serviceAuthService.setAuthEnabled(appId, serviceId, enabled))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "撤销授权（逻辑删除，可重新授权）")
    @DeleteMapping(UrlConstant.REVOKE)
    public Mono<Result<Void>> revoke(
            @Parameter(description = "接入系统 ID") @RequestParam("appId") String appId,
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId) {
        return Mono.fromRunnable(() -> serviceAuthService.revoke(appId, serviceId))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }
}
