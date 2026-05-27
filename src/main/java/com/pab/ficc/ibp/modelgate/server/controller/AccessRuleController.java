package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.AddRuleRequest;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AccessRuleVO;
import com.pab.ficc.ibp.modelgate.server.service.AccessRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/access")
@RequiredArgsConstructor
public class AccessRuleController {

    private final AccessRuleService accessRuleService;

    @GetMapping("/list")
    public Mono<Result<List<AccessRuleVO>>> list(@RequestParam String appId) {
        return Mono.fromCallable(() -> accessRuleService.listByAppId(appId)
                        .stream().map(AccessRuleVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping("/add")
    public Mono<Result<AccessRuleVO>> add(@RequestBody @Valid AddRuleRequest req) {
        return Mono.fromCallable(() -> AccessRuleVO.of(
                        accessRuleService.add(req.getAppId(), req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @DeleteMapping("/delete")
    public Mono<Result<Void>> delete(@RequestParam String appId, @RequestParam Long id) {
        return Mono.fromRunnable(() -> accessRuleService.delete(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }
}
