package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.AddRuleRequest;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AccessRuleVO;
import com.pab.ficc.ibp.modelgate.server.service.AccessRuleService;
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

@Tag(name = "访问控制", description = "配置接入系统的 IP / CIDR / 域名来源白名单")
@RestController
@RequestMapping("/admin/access")
@RequiredArgsConstructor
public class AccessRuleController {

    private final AccessRuleService accessRuleService;

    @Operation(summary = "查询系统访问规则列表")
    @GetMapping("/list")
    public Mono<Result<List<AccessRuleVO>>> list(
            @Parameter(description = "系统 ID") @RequestParam String appId) {
        return Mono.fromCallable(() -> accessRuleService.listByAppId(appId)
                        .stream().map(AccessRuleVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @Operation(summary = "新增访问规则（ip / cidr / domain）")
    @PostMapping("/add")
    public Mono<Result<AccessRuleVO>> add(@RequestBody @Valid AddRuleRequest req) {
        return Mono.fromCallable(() -> AccessRuleVO.of(
                        accessRuleService.add(req.getAppId(), req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @Operation(summary = "删除访问规则")
    @DeleteMapping("/delete")
    public Mono<Result<Void>> delete(
            @Parameter(description = "系统 ID") @RequestParam String appId,
            @Parameter(description = "规则 ID") @RequestParam Long id) {
        return Mono.fromRunnable(() -> accessRuleService.delete(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }
}
