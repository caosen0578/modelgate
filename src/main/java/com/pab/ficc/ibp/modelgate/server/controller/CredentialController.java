package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.CreateCredentialRequest;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredential;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AppCredentialVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.CredentialCreatedVO;
import com.pab.ficc.ibp.modelgate.server.service.CredentialService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.validation.Valid;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @GetMapping("/list")
    public Mono<Result<List<AppCredentialVO>>> list(@RequestParam String appId) {
        return Mono.fromCallable(() -> credentialService.listByAppId(appId)
                        .stream().map(AppCredentialVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping("/create")
    public Mono<Result<CredentialCreatedVO>> create(@RequestBody @Valid CreateCredentialRequest req) {
        return Mono.fromCallable(() -> {
            AppCredential cred = req.toEntity();
            CredentialService.CreatedCredential created = credentialService.create(req.getAppId(), cred);
            AppCredential saved = created.credential();
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
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::ok);
    }

    @PatchMapping("/enable")
    public Mono<Result<Void>> enable(@RequestParam String appId, @RequestParam Long id) {
        return Mono.fromRunnable(() -> credentialService.enable(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PatchMapping("/disable")
    public Mono<Result<Void>> disable(@RequestParam String appId, @RequestParam Long id) {
        return Mono.fromRunnable(() -> credentialService.disable(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @DeleteMapping("/delete")
    public Mono<Result<Void>> delete(@RequestParam String appId, @RequestParam Long id) {
        return Mono.fromRunnable(() -> credentialService.delete(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }
}
