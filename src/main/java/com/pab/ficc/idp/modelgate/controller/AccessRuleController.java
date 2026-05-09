package com.pab.ficc.idp.modelgate.controller;

import com.pab.ficc.idp.modelgate.common.Result;
import com.pab.ficc.idp.modelgate.entity.AccessRule;
import com.pab.ficc.idp.modelgate.service.AccessRuleService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/admin/apps/{appId}/access")
@RequiredArgsConstructor
public class AccessRuleController {

    private final AccessRuleService accessRuleService;

    @GetMapping
    public Mono<Result<List<AccessRule>>> list(@PathVariable String appId) {
        return Mono.fromCallable(() -> accessRuleService.listByAppId(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping
    public Mono<Result<AccessRule>> add(@PathVariable String appId,
                                        @RequestBody @Valid AddRuleRequest req) {
        return Mono.fromCallable(() -> accessRuleService.add(appId, req.toEntity()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(@PathVariable String appId, @PathVariable Long id) {
        return Mono.fromRunnable(() -> accessRuleService.delete(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    // ===== DTO =====

    @Data
    public static class AddRuleRequest {
        @NotBlank private String ruleType;
        @NotBlank private String ruleValue;
        private String remark;

        AccessRule toEntity() {
            var r = new AccessRule();
            r.setRuleType(ruleType);
            r.setRuleValue(ruleValue);
            r.setRemark(remark);
            return r;
        }
    }
}
