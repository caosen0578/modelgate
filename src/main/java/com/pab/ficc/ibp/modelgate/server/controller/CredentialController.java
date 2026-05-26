package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredential;
import com.pab.ficc.ibp.modelgate.server.service.CredentialService;
import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @GetMapping("/list")
    public Mono<Result<List<AppCredential>>> list(@RequestParam String appId) {
        return Mono.fromCallable(() -> credentialService.listByAppId(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping("/create")
    public Mono<Result<Map<String, Object>>> create(@RequestBody @Valid CreateCredentialRequest req) {
        return Mono.fromCallable(() -> {
            AppCredential cred = req.toEntity();
            CredentialService.CreatedCredential created = credentialService.create(req.getAppId(), cred);
            AppCredential saved = created.credential();
            String plain = created.plainSecret();

            if ("ak_sk".equals(saved.getCredType())) {
                return Map.<String, Object>of(
                        "id", saved.getId(),
                        "credType", "ak_sk",
                        "accessKey", saved.getAccessKey(),
                        "secretKey", plain,
                        "expiresAt", saved.getExpiresAt() != null ? saved.getExpiresAt().toString() : null
                );
            } else {
                return Map.<String, Object>of(
                        "id", saved.getId(),
                        "credType", "api_key",
                        "apiKey", plain,
                        "expiresAt", saved.getExpiresAt() != null ? saved.getExpiresAt().toString() : null
                );
            }
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

    // ===== DTO =====

    @Data
    public static class CreateCredentialRequest {
        @NotBlank private String appId;
        @NotBlank private String credName;
        @NotBlank private String credType;
        private LocalDateTime expiresAt;
        private String remark;

        AppCredential toEntity() {
            var c = new AppCredential();
            c.setCredName(credName);
            c.setCredType(credType);
            c.setExpiresAt(expiresAt);
            c.setRemark(remark);
            return c;
        }
    }
}
