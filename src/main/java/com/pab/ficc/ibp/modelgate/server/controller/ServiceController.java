package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.domain.base.vo.PageVo;
import com.pab.ficc.ibp.modelgate.server.common.AdminScheduler;
import com.pab.ficc.ibp.modelgate.server.common.PageSupport;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.CreateServiceRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.ServiceQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpdateServiceRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ConnectivityTestVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ServiceVO;
import com.pab.ficc.ibp.modelgate.server.service.ServiceAuthService;
import com.pab.ficc.ibp.modelgate.server.service.ServiceConnectivityService;
import com.pab.ficc.ibp.modelgate.server.service.ServiceRegistryService;
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

import java.util.HashMap;
import java.util.Map;

/**
 * 服务管理（通用服务注册）。
 *
 * <p><b>所有 DB 操作必须切出 Netty 事件循环线程</b>：这是 WebFlux 项目，
 * MyBatis/Druid 是同步阻塞的，直接跑在事件循环上会拖垮<b>整个进程</b>的转发能力——
 * 被拖慢的不只是管理接口，而是所有在途的业务请求。见 spec/modelgate/05 §WebFlux 线程禁令。
 *
 * <p>切到 {@link AdminScheduler} 而不是公共的 {@code boundedElastic}：管理面查询有独立的
 * 并发上限（8）与排队上限（128），混进公共池会让一条慢查询挤占业务转发的线程。
 * 配套的 {@code timeout(REQUEST_TIMEOUT)} 是兜底，与 {@code ModelController} 同一套约定。
 */
@Slf4j
@Tag(name = "服务管理", description = "通用服务注册：/svc/{服务编码}/** 流量的上游目标与凭证")
@RestController
@RequestMapping(UrlConstant.MG_SERVICES)
@RequiredArgsConstructor
public class ServiceController {

    private final ServiceRegistryService serviceRegistryService;
    private final ServiceAuthService serviceAuthService;
    private final ServiceConnectivityService connectivityService;

    /**
     * 服务列表。
     *
     * <p><b>返回类型必须是 {@code PageVo}，不能自己拼 Map</b>：前端的 {@code applyPage}
     * 读的是 {@code items} / {@code total} / {@code pageIndex} / {@code pageSize} / {@code pages}
     * 这五个字段（{@code PageVo} 的字段名）。第一版这里手拼了一个用 {@code records} 当键的 Map，
     * 结果 {@code pv.items} 取到 undefined、列表恒空，而 {@code total} 恰好给对了——
     * 页面表现为「顶上写着共 1 个服务、表格里一行没有」，看起来像数据没存进去。
     *
     * <p>同理走 {@code AdminScheduler} 而不是 {@code boundedElastic}：管理面查询有独立的
     * 并发上限与排队上限，混进公共池会让一条慢查询挤占业务转发的线程。
     * {@code PageSupport.validate} 与 {@code timeout} 也是管理面列表接口的统一约定。
     */
    @Operation(summary = "服务列表（分页 + 关键字/状态筛选，带授权系统数量）")
    @GetMapping(UrlConstant.ROOT)
    public Mono<Result<PageVo<ServiceVO>>> list(ServiceQueryBO query) {
        // 分页护栏放在 Mono 外：参数非法应当立刻返回，不必占用管理面线程池的名额
        PageSupport.validate(query, "服务");
        return Mono.fromCallable(() -> {
                    // 凭证与授权计数各一次性取全，避免列表逐行查询造成 N+1。
                    // 两张表整表加载与分页无关——分页只收敛服务主表的返回行数。
                    Map<String, ServiceCredentialEntity> credentials = serviceRegistryService.findAllCredentials();
                    Map<String, Long> authCounts = serviceAuthService.countByServiceId();
                    return PageVo.of(serviceRegistryService.page(query), e -> {
                        ServiceVO vo = ServiceVO.of(e, credentials.get(e.getServiceId()));
                        vo.setAuthorizedAppCount(authCounts.getOrDefault(e.getServiceId(), 0L));
                        return vo;
                    });
                })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "服务详情")
    @GetMapping(UrlConstant.DETAIL)
    public Mono<Result<ServiceVO>> detail(
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId) {
        return Mono.fromCallable(() -> {
            ServiceEntity e = serviceRegistryService.findByServiceId(serviceId);
            return ServiceVO.of(e, serviceRegistryService.getCredential(serviceId).orElse(null));
        })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "新增服务（含上游凭证，事务写两表）")
    @PostMapping(UrlConstant.ROOT)
    public Mono<Result<ServiceVO>> create(@RequestBody @Valid CreateServiceRequestBO req) {
        return Mono.fromCallable(() -> ServiceVO.of(serviceRegistryService.create(req)))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "修改服务（服务编码不可修改，凭证字段留空=保持原值）")
    @PutMapping(UrlConstant.UPDATE)
    public Mono<Result<ServiceVO>> update(@RequestBody @Valid UpdateServiceRequestBO req) {
        return Mono.fromCallable(() -> {
            ServiceEntity e = serviceRegistryService.update(req);
            return ServiceVO.of(e, serviceRegistryService.getCredential(e.getServiceId()).orElse(null));
        })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "删除服务（逻辑删除，级联软删凭证与全部授权行）")
    @DeleteMapping(UrlConstant.DELETE)
    public Mono<Result<Void>> delete(
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId) {
        return Mono.fromRunnable(() -> serviceRegistryService.delete(serviceId))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "启用服务")
    @PatchMapping(UrlConstant.ENABLE)
    public Mono<Result<Void>> enable(@RequestParam("serviceId") String serviceId) {
        return Mono.fromRunnable(() -> serviceRegistryService.enable(serviceId))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "禁用服务（禁用后请求该服务返回 503）")
    @PatchMapping(UrlConstant.DISABLE)
    public Mono<Result<Void>> disable(@RequestParam("serviceId") String serviceId) {
        return Mono.fromRunnable(() -> serviceRegistryService.disable(serviceId))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }

    /**
     * 上游连通性探测。
     *
     * <p><b>它回答的问题比模型侧的同名接口弱，别混淆</b>：通用服务没有统一的接口形状，
     * 网关不知道哪个路径可用、要什么请求体，所以只能验证"网关能不能连到这个上游地址"。
     * 拿到任何 HTTP 响应（哪怕 404）都判连通——那恰恰证明 DNS、网络策略、端口、TLS 全通了。
     * 详见 {@link ServiceConnectivityService} 类注释。
     *
     * <p>要验证端到端全链路（凭证鉴权 → 访问控制 → 服务授权 → 配额 → 路由 → 上游），
     * 请用「凭证管理 → 接入示例 → 测试连通」，那个是按接入方身份打真实的 {@code /svc/**}。
     *
     * <p>探测用 <b>GET</b> 且<b>不对上游产生副作用</b>：通用服务的 POST 往往是业务写操作，
     * 拿它去测连通可能真的建一条数据出来。
     *
     * <p>方法 / 路径 / 请求体三项<b>留空则取服务录入时登记的探测配置</b>
     * （{@code mg_service.probe_method / probe_path / probe_body}），传了则临时覆盖本次探测。
     * 这样"平时点一下测试"用登记好的方式，"临时换个路径试试"也不用改配置。
     *
     * <p>⚠️ 登记为非 GET 时，探测会对上游产生<b>真实副作用</b>——网关无法替业务判断
     * 哪个接口是幂等的，这件事由录入者决定，服务端会打 WARN 留痕。
     */
    @Operation(summary = "上游连通性探测（按服务登记的方法/路径；判定的是「网关能否连到上游」）")
    @PostMapping(UrlConstant.TEST)
    public Mono<Result<ConnectivityTestVO>> test(
            @Parameter(description = "服务编码") @RequestParam("serviceId") String serviceId,
            @Parameter(description = "探测方法，留空取服务登记值")
            @RequestParam(name = "probeMethod", required = false) String probeMethod,
            @Parameter(description = "探测路径，留空取服务登记值")
            @RequestParam(name = "probePath", required = false) String probePath,
            @Parameter(description = "探测请求体，留空取服务登记值；GET/HEAD 忽略")
            @RequestParam(name = "probeBody", required = false) String probeBody) {
        // 装载服务与凭证要查库，必须先切到管理面线程池；探测本身是响应式的，不阻塞
        return Mono.fromCallable(() -> {
                    ServiceEntity svc = serviceRegistryService.findByServiceId(serviceId);
                    return new Object[]{svc, serviceRegistryService.getCredentialDecrypted(serviceId)};
                })
                .subscribeOn(AdminScheduler.get())
                .flatMap(arr -> connectivityService.probe(
                        (ServiceEntity) arr[0],
                        (com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceCredentialEntity) arr[1],
                        probeMethod, probePath, probeBody))
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
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
        })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }
}
