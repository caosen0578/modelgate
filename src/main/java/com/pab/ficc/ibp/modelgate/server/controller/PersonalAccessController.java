package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.domain.base.vo.PageVo;
import com.pab.ficc.ibp.modelgate.server.common.AdminScheduler;
import com.pab.ficc.ibp.modelgate.server.common.PageSupport;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AppQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.ModelQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.PersonalAccessRequests;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppCredentialVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PersonalAccessViews;
import com.pab.ficc.ibp.modelgate.server.service.PersonalAccessService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.Callable;

@RestController
@RequestMapping(UrlConstant.MG_PERSONAL_ACCESS)
@RequiredArgsConstructor
@Tag(name = "个人接入", description = "管理员按 UM 管理个人凭证、模型授权和 Token 用量")
public class PersonalAccessController {
    private final PersonalAccessService personal;

    @Operation(summary = "查询个人接入开关")
    @GetMapping(UrlConstant.META)
    public Mono<Result<PersonalAccessViews.Meta>> meta() {
        return Mono.just(Result.success(personal.meta()));
    }

    @Operation(summary = "分页查询个人接入")
    @GetMapping(UrlConstant.PAGE)
    public Mono<Result<PageVo<PersonalAccessViews.Profile>>> page(AppQueryBO query) {
        PageSupport.validate(query, "个人接入");
        return read(() -> PageVo.of(personal.page(query), PersonalAccessViews.Profile::of));
    }

    @Operation(summary = "按 UM 查看个人接入详情")
    @GetMapping(UrlConstant.DETAIL)
    public Mono<Result<PersonalAccessViews.Profile>> detail(@RequestParam("um") String um) {
        return read(() -> personal.detail(um));
    }

    @Operation(summary = "开通个人接入")
    @PostMapping(UrlConstant.CREATE)
    public Mono<Result<PersonalAccessViews.Profile>> create(@RequestBody @Valid PersonalAccessRequests.Profile request) {
        return read(() -> personal.create(request));
    }

    @Operation(summary = "批量导入个人用户")
    @PostMapping("/import")
    public Mono<Result<PersonalAccessViews.BatchResult>> importUsers(
            @RequestBody @Valid PersonalAccessRequests.ImportUsers request) {
        return read(() -> personal.importUsers(request));
    }

    @Operation(summary = "批量新增个人模型授权")
    @PostMapping("/auth/batch")
    public Mono<Result<PersonalAccessViews.BatchResult>> batchGrant(
            @RequestBody @Valid PersonalAccessRequests.BatchGrant request) {
        return read(() -> personal.batchGrant(request));
    }

    @Operation(summary = "更新个人姓名与备注（不修改 UM）")
    @PutMapping(UrlConstant.UPDATE)
    public Mono<Result<PersonalAccessViews.Profile>> update(@RequestBody @Valid PersonalAccessRequests.Profile request) {
        return read(() -> personal.update(request));
    }

    @Operation(summary = "启用/停用个人接入")
    @PatchMapping(UrlConstant.TOGGLE)
    public Mono<Result<Void>> toggle(@RequestParam("um") String um, @RequestParam("enabled") boolean enabled) {
        return write(() -> personal.toggle(um, enabled));
    }

    @Operation(summary = "按 UM 查询个人凭证（不含明文）")
    @GetMapping(UrlConstant.PERSONAL_CREDENTIAL_LIST)
    public Mono<Result<List<AppCredentialVO>>> credentials(@RequestParam("um") String um) {
        return read(() -> personal.credentials(um));
    }

    @Operation(summary = "签发个人 API Key（返回当次签发明文）")
    @PostMapping(UrlConstant.PERSONAL_CREDENTIAL_CREATE)
    public Mono<Result<PersonalAccessViews.IssuedKey>> issue(
            @RequestBody @Valid PersonalAccessRequests.Credential request, ServerHttpResponse response) {
        response.getHeaders().setCacheControl(CacheControl.noStore());
        return read(() -> personal.issue(request));
    }

    @Operation(summary = "查看单条个人 API Key 明文（独立权限，按 UM 校验归属）")
    @GetMapping(UrlConstant.PERSONAL_CREDENTIAL_SECRET)
    public Mono<Result<String>> revealKey(@RequestParam("um") String um, @RequestParam("id") Long id,
                                          ServerHttpResponse response) {
        response.getHeaders().setCacheControl(CacheControl.noStore());
        return read(() -> personal.revealKey(um, id));
    }

    @Operation(summary = "撤销个人 API Key")
    @DeleteMapping(UrlConstant.PERSONAL_CREDENTIAL_REVOKE)
    public Mono<Result<Void>> revoke(@RequestParam("um") String um, @RequestParam("id") Long id) {
        return write(() -> personal.revoke(um, id));
    }

    @Operation(summary = "分页查询个人可授权的已启用模型")
    @GetMapping(UrlConstant.PERSONAL_AUTH_MODELS)
    public Mono<Result<PageVo<PersonalAccessViews.ModelOption>>> models(ModelQueryBO query) {
        PageSupport.validate(query, "个人可授权模型");
        return read(() -> PageVo.of(personal.models(query), PersonalAccessViews.ModelOption::of));
    }

    @Operation(summary = "查询个人模型授权")
    @GetMapping(UrlConstant.PERSONAL_AUTH_LIST)
    public Mono<Result<List<PersonalAccessViews.Grant>>> grants(@RequestParam("um") String um) {
        return read(() -> personal.grants(um));
    }

    @Operation(summary = "保存个人模型授权及配额")
    @PutMapping(UrlConstant.PERSONAL_AUTH_SAVE)
    public Mono<Result<Void>> saveGrant(@RequestBody @Valid PersonalAccessRequests.ModelGrant request) {
        return write(() -> personal.saveGrant(request));
    }

    @Operation(summary = "启用/停用个人模型授权")
    @PatchMapping(UrlConstant.PERSONAL_AUTH_TOGGLE)
    public Mono<Result<Void>> toggleGrant(@RequestParam("um") String um, @RequestParam("modelId") String modelId,
                                          @RequestParam("enabled") boolean enabled) {
        return write(() -> personal.toggleGrant(um, modelId, enabled));
    }

    @Operation(summary = "按 UM 查询个人来源白名单")
    @GetMapping("/access/list")
    public Mono<Result<List<PersonalAccessViews.SourceRule>>> sourceRules(@RequestParam("um") String um) {
        return read(() -> personal.sourceRules(um));
    }

    @Operation(summary = "按 UM 新增个人 IP / CIDR 白名单规则")
    @PostMapping("/access/add")
    public Mono<Result<PersonalAccessViews.SourceRule>> addSourceRule(
            @RequestBody @Valid PersonalAccessRequests.SourceRule request) {
        return read(() -> personal.addSourceRule(request));
    }

    @Operation(summary = "按 UM 删除个人来源规则")
    @DeleteMapping("/access/delete")
    public Mono<Result<Void>> deleteSourceRule(@RequestParam("um") String um, @RequestParam("id") Long id) {
        return write(() -> personal.deleteSourceRule(um, id));
    }

    @Operation(summary = "按 UM 和日期汇总个人 Token 用量")
    @GetMapping(UrlConstant.USAGE)
    public Mono<Result<PersonalAccessViews.Usage>> usage(@RequestParam("um") String um,
            @RequestParam(name = "start", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(name = "end", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {
        return read(() -> personal.usage(um, start, end));
    }

    private static <T> Mono<Result<T>> read(Callable<T> work) {
        return Mono.fromCallable(work).subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT).map(Result::success);
    }

    private static Mono<Result<Void>> write(Runnable work) {
        return Mono.fromRunnable(work).subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT).thenReturn(Result.<Void>success(null));
    }
}
