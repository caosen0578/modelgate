package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AccessRule;
import com.pab.ficc.ibp.modelgate.server.service.AccessRuleService;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

@RestController
@RequestMapping("/admin/access")
@RequiredArgsConstructor
public class AccessRuleController {

    private final AccessRuleService accessRuleService;

    @GetMapping("/list")
    public Mono<Result<List<AccessRule>>> list(@RequestParam String appId) {
        return Mono.fromCallable(() -> accessRuleService.listByAppId(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping("/add")
    public Mono<Result<AccessRule>> add(@RequestBody @Valid AddRuleRequest req) {
        return Mono.fromCallable(() -> accessRuleService.add(req.getAppId(), req.toEntity()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @DeleteMapping("/delete")
    public Mono<Result<Void>> delete(@RequestParam String appId, @RequestParam Long id) {
        return Mono.fromRunnable(() -> accessRuleService.delete(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    // ===== DTO =====

    @Data
    public static class AddRuleRequest {
        @NotBlank private String appId;
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
