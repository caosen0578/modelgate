package com.pab.ficc.idp.modelgate.controller;

import com.pab.ficc.idp.modelgate.common.Result;
import com.pab.ficc.idp.modelgate.entity.ModelCredential;
import com.pab.ficc.idp.modelgate.entity.ModelInstance;
import com.pab.ficc.idp.modelgate.service.ModelService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/admin/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @GetMapping
    public Mono<Result<List<ModelInstance>>> findAll() {
        return Mono.fromCallable(modelService::findAll)
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @GetMapping("/{modelId}")
    public Mono<Result<ModelInstance>> findOne(@PathVariable String modelId) {
        return Mono.fromCallable(() -> modelService.findByModelId(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping
    public Mono<Result<ModelInstance>> create(@RequestBody @Valid CreateModelRequest req) {
        return Mono.fromCallable(() -> {
            ModelInstance model = req.toEntity();
            ModelCredential cred = req.toCredential();
            return modelService.create(model, cred);
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::ok);
    }

    @PutMapping("/{modelId}")
    public Mono<Result<ModelInstance>> update(@PathVariable String modelId,
                                               @RequestBody @Valid UpdateModelRequest req) {
        return Mono.fromCallable(() -> modelService.update(modelId, req.toEntity()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PatchMapping("/{modelId}/enable")
    public Mono<Result<Void>> enable(@PathVariable String modelId) {
        return Mono.fromRunnable(() -> modelService.enable(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PatchMapping("/{modelId}/disable")
    public Mono<Result<Void>> disable(@PathVariable String modelId) {
        return Mono.fromRunnable(() -> modelService.disable(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @DeleteMapping("/{modelId}")
    public Mono<Result<Void>> delete(@PathVariable String modelId) {
        return Mono.fromRunnable(() -> modelService.delete(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PostMapping("/{modelId}/test")
    public Mono<Result<Map<String, Object>>> test(@PathVariable String modelId) {
        return Mono.fromCallable(() -> {
            modelService.findByModelId(modelId);
            return Map.<String, Object>of(
                    "success", true,
                    "latencyMs", 0,
                    "message", "Model exists (connectivity test not yet implemented)"
            );
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::ok);
    }

    // ===== DTOs =====

    @Data
    public static class CreateModelRequest {
        @NotBlank private String modelId;
        @NotBlank private String modelName;
        @NotBlank private String provider;
        private String modelType = "chat";
        private String apiSpec = "openai";
        @NotBlank private String upstreamUrl;
        private Integer timeoutMs = 30000;
        private String defaultParams;
        private String forceParams;
        private String description;
        private CredentialRequest credential;

        ModelInstance toEntity() {
            var m = new ModelInstance();
            m.setModelId(modelId);
            m.setModelName(modelName);
            m.setProvider(provider);
            m.setModelType(modelType != null ? modelType : "chat");
            m.setApiSpec(apiSpec != null ? apiSpec : "openai");
            m.setUpstreamUrl(upstreamUrl);
            m.setTimeoutMs(timeoutMs != null ? timeoutMs : 30000);
            m.setDefaultParams(defaultParams);
            m.setForceParams(forceParams);
            m.setDescription(description);
            m.setEnabled(true);
            return m;
        }

        ModelCredential toCredential() {
            if (credential == null) return null;
            var c = new ModelCredential();
            c.setAuthType(credential.getAuthType());
            c.setAccessKey(credential.getAccessKey());
            c.setSecretKey(credential.getSecretKey());
            c.setExtraConfig(credential.getExtraConfig());
            return c;
        }
    }

    @Data
    public static class UpdateModelRequest {
        @NotBlank private String modelName;
        @NotBlank private String provider;
        private String modelType;
        private String apiSpec;
        @NotBlank private String upstreamUrl;
        private Integer timeoutMs;
        private String defaultParams;
        private String forceParams;
        private String description;

        ModelInstance toEntity() {
            var m = new ModelInstance();
            m.setModelName(modelName);
            m.setProvider(provider);
            m.setModelType(modelType);
            m.setApiSpec(apiSpec);
            m.setUpstreamUrl(upstreamUrl);
            m.setTimeoutMs(timeoutMs);
            m.setDefaultParams(defaultParams);
            m.setForceParams(forceParams);
            m.setDescription(description);
            return m;
        }
    }

    @Data
    public static class CredentialRequest {
        private String authType;
        private String accessKey;
        private String secretKey;
        private String extraConfig;
    }
}
