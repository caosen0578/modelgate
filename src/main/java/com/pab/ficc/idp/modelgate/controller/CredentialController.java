package com.pab.ficc.idp.modelgate.controller;

import com.pab.ficc.idp.modelgate.common.Result;
import com.pab.ficc.idp.modelgate.entity.AppCredential;
import com.pab.ficc.idp.modelgate.service.CredentialService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/apps/{appId}/credentials")
@RequiredArgsConstructor
public class CredentialController {

    private final CredentialService credentialService;

    @GetMapping
    public Mono<Result<List<AppCredential>>> list(@PathVariable String appId) {
        return Mono.fromCallable(() -> credentialService.listByAppId(appId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping
    public Mono<Result<Map<String, Object>>> create(@PathVariable String appId,
                                                     @RequestBody @Valid CreateCredentialRequest req) {
        return Mono.fromCallable(() -> {
            AppCredential cred = req.toEntity();
            CredentialService.CreatedCredential created = credentialService.create(appId, cred);
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

    @PatchMapping("/{id}/enable")
    public Mono<Result<Void>> enable(@PathVariable String appId, @PathVariable Long id) {
        return Mono.fromRunnable(() -> credentialService.enable(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PatchMapping("/{id}/disable")
    public Mono<Result<Void>> disable(@PathVariable String appId, @PathVariable Long id) {
        return Mono.fromRunnable(() -> credentialService.disable(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @DeleteMapping("/{id}")
    public Mono<Result<Void>> delete(@PathVariable String appId, @PathVariable Long id) {
        return Mono.fromRunnable(() -> credentialService.delete(appId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    // ===== DTO =====

    @Data
    public static class CreateCredentialRequest {
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
