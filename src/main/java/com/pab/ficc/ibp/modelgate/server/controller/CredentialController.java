package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.common.UrlConstant;

import com.pab.ficc.cus.web.result.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.CreateCredentialRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppCredentialVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.CredentialCreatedVO;
import com.pab.ficc.ibp.modelgate.server.service.CredentialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "凭证管理", description = "为接入系统生成和管理 API Key / AK-SK 访问凭证")
@RestController
@RequestMapping(UrlConstant.ADMIN_CREDENTIALS)
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @Operation(summary = "查询系统凭证列表")
    @GetMapping(UrlConstant.LIST)
    public Mono<Result<List<AppCredentialVO>>> list(
            @Parameter(description = "系统 ID") @RequestParam String appId) {
        return Mono.fromCallable(() -> credentialService.listByAppId(appId)
                        .stream().map(AppCredentialVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "生成凭证（API Key 或 AK-SK，Secret 仅此时明文返回一次）")
    @PostMapping(UrlConstant.CREATE)
    public Mono<Result<CredentialCreatedVO>> create(@RequestBody @Valid CreateCredentialRequestBO req) {
        return Mono.fromCallable(() -> {
            AppCredentialEntity cred = req.toEntity();
            CredentialService.CreatedCredential created = credentialService.create(req.getAppId(), cred);
            AppCredentialEntity saved = created.credential();
            String plain = created.plainSecret();

            CredentialCreatedVO.CredentialCreatedVOBuilder builder = CredentialCreatedVO.builder()
                    .id(saved.getId())
                    .credType(saved.getCredType())
                    .expiresAt(saved.getExpiresAt());

            if ("ak_sk".equals(saved.getCredType())) {
                builder.accessKey(saved.getAccessKey()).secretKey(plain);
            } else {
                builder.apiKey(plain);
            }
            return builder.build();
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::success);
    }

    @Operation(summary = "启用凭证")
    @PatchMapping(UrlConstant.ENABLE)
    public Mono<Result<Void>> enable(
            @Parameter(description = "系统 ID") @RequestParam String appId,
            @Parameter(description = "凭证 ID") @RequestParam Long id) {
        return Mono.fromRunnable(() -> credentialService.enable(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "禁用凭证")
    @PatchMapping(UrlConstant.DISABLE)
    public Mono<Result<Void>> disable(
            @Parameter(description = "系统 ID") @RequestParam String appId,
            @Parameter(description = "凭证 ID") @RequestParam Long id) {
        return Mono.fromRunnable(() -> credentialService.disable(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "删除凭证（立即失效）")
    @DeleteMapping(UrlConstant.DELETE)
    public Mono<Result<Void>> delete(
            @Parameter(description = "系统 ID") @RequestParam String appId,
            @Parameter(description = "凭证 ID") @RequestParam Long id) {
        return Mono.fromRunnable(() -> credentialService.delete(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }
}
