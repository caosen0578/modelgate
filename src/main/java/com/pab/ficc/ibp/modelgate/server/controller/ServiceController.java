package com.pab.ficc.ibp.modelgate.server.controller;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.CreateServiceRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.ServiceQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpdateServiceRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ServiceAuthorizedAppVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ServiceVO;
import com.pab.ficc.ibp.modelgate.server.service.ServiceAuthService;
import com.pab.ficc.ibp.modelgate.server.service.ServiceRegistryService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务管理（通用服务注册）。
 *
 * <p><b>所有 DB 操作必须 {@code subscribeOn(Schedulers.boundedElastic())}</b>：
 * 这是 WebFlux 项目，MyBatis/Druid 是同步阻塞的，直接跑在 Netty 事件循环线程上
 * 会拖垮<b>整个进程</b>的转发能力——被拖慢的不只是管理接口，而是所有在途的业务请求。
 * 这条约束见 spec/modelgate/05 §WebFlux 线程禁令。
 */
@Slf4j
@Tag(name = "服务管理", description = "通用服务注册：/svc/{服务编码}/** 流量的上游目标与凭证")
@RestController
@RequestMapping(UrlConstant.MG_SERVICES)
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceRegistryService serviceRegistryService;
    private final ServiceAuthService serviceAuthService;

    @Operation(summary = "服务列表（分页 + 关键字/状态筛选，带授权系统数量）")
    @GetMapping(UrlConstant.ROOT)
    public Mono<Result<Map<String, Object>>> list(ServiceQueryBO query) {
        return Mono.fromCallable(() -> {
            IPage<ServiceEntity> page = serviceRegistryService.page(query);
            // 授权数量一次性聚合，避免列表逐行查询造成 N+1
            Map<String, Long> counts = serviceAuthService.countByServiceId();
            List<ServiceVO> records = page.getRecords().stream().map(e -> {
                ServiceVO vo = ServiceVO.of(e, serviceRegistryService.getCredential(e.getServiceId()).orElse(null));
                vo.setAuthorizedAppCount(counts.getOrDefault(e.getServiceId(), 0L));
                return vo;
            }).toList();

            Map<String, Object> data = new HashMap<>();
            data.put("records", records);
            data.put("total", page.getTotal());
            data.put("pageIndex", page.getCurrent());
            data.put("pageSize", page.getSize());
            return data;
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::success);
    }

    @Operation(summary = "服务详情")
    @GetMapping(UrlConstant.DETAIL)
    public Mono<Result<ServiceVO>> detail(
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId) {
        return Mono.fromCallable(() -> {
            ServiceEntity e = serviceRegistryService.findByServiceId(serviceId);
            return ServiceVO.of(e, serviceRegistryService.getCredential(serviceId).orElse(null));
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::success);
    }

    @Operation(summary = "新增服务（含上游凭证，事务写两表）")
    @PostMapping(UrlConstant.CREATE)
    public Mono<Result<ServiceVO>> create(@RequestBody @Valid CreateServiceRequestBO req) {
        return Mono.fromCallable(() -> ServiceVO.of(serviceRegistryService.create(req)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "修改服务（服务编码不可修改，凭证字段留空=保持原值）")
    @PostMapping(UrlConstant.UPDATE)
    public Mono<Result<ServiceVO>> update(@RequestBody @Valid UpdateServiceRequestBO req) {
        return Mono.fromCallable(() -> {
            ServiceEntity e = serviceRegistryService.update(req);
            return ServiceVO.of(e, serviceRegistryService.getCredential(e.getServiceId()).orElse(null));
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::success);
    }

    @Operation(summary = "删除服务（逻辑删除，级联软删凭证与全部授权行）")
    @PostMapping(UrlConstant.DELETE)
    public Mono<Result<Void>> delete(
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId) {
        return Mono.fromRunnable(() -> serviceRegistryService.delete(serviceId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "启用服务")
    @PostMapping(UrlConstant.ENABLE)
    public Mono<Result<Void>> enable(@RequestParam("serviceId") String serviceId) {
        return Mono.fromRunnable(() -> serviceRegistryService.enable(serviceId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "禁用服务（禁用后请求该服务返回 503）")
    @PostMapping(UrlConstant.DISABLE)
    public Mono<Result<Void>> disable(@RequestParam("serviceId") String serviceId) {
        return Mono.fromRunnable(() -> serviceRegistryService.disable(serviceId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    /**
     * 查看上游凭证明文。
     *
     * <p><b>单条按需解密，禁止用于列表</b>：列表一次返回几十条，任何一条泄露都等于
     * 把上游凭证发给了浏览器。{@code ServiceVO} 因此只给"是否已配置"。
     */
    @Operation(summary = "查看上游凭证明文（单条按需解密，禁止用于列表）")
    @GetMapping(UrlConstant.SECRET)
    public Mono<Result<Map<String, String>>> secret(
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId) {
        return Mono.fromCallable(() -> {
            serviceRegistryService.findByServiceId(serviceId);   // 不存在直接 404，不泄露"有没有这个服务"之外的信息
            ServiceCredentialEntity c = serviceRegistryService.getCredentialDecrypted(serviceId);
            Map<String, String> m = new HashMap<>();
            m.put("authType", c == null ? "none" : c.getAuthType());
            m.put("accessKey", c == null ? null : c.getAccessKey());
            m.put("secretKey", c == null ? null : c.getSecretKey());
            m.put("extraHeaders", c == null ? null : c.getExtraHeaders());
            return m;
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::success);
    }

    @Operation(summary = "反查：某服务被哪些接入系统授权（含是否实际生效）")
    @GetMapping(UrlConstant.APPS)
    public Mono<Result<List<ServiceAuthorizedAppVO>>> listAuthorizedApps(
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId) {
        return Mono.fromCallable(() -> serviceAuthService.listAuthorizedApps(serviceId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }
}
