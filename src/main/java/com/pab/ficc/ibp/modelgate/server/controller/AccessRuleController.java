package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AddRuleRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AccessRuleVO;
import com.pab.ficc.ibp.modelgate.server.service.AccessRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import jakarta.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@Tag(name = "访问控制", description = "配置接入系统的 IP / CIDR / 域名来源白名单")
@RestController
@RequestMapping(UrlConstant.MG_ACCESS)
@RequiredArgsConstructor
public class AccessRuleController {

    private final AccessRuleService accessRuleService;

    @Operation(summary = "查询系统访问规则列表")
    @GetMapping(UrlConstant.LIST)
    public Mono<Result<List<AccessRuleVO>>> list(
            @Parameter(description = "系统 ID") @RequestParam("appId") String appId) {
        return Mono.fromCallable(() -> accessRuleService.listByAppId(appId)
                        .stream().map(AccessRuleVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "新增访问规则（ip / cidr / domain）")
    @PostMapping(UrlConstant.ADD)
    public Mono<Result<AccessRuleVO>> add(@RequestBody @Valid AddRuleRequestBO req) {
        return Mono.fromCallable(() -> AccessRuleVO.of(
                        accessRuleService.add(req.getAppId(), req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @Operation(summary = "删除访问规则")
    @DeleteMapping(UrlConstant.DELETE)
    public Mono<Result<Void>> delete(
            @Parameter(description = "系统 ID") @RequestParam("appId") String appId,
            @Parameter(description = "规则 ID") @RequestParam("id") Long id) {
        return Mono.fromRunnable(() -> accessRuleService.delete(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>success(null)));
    }
}
