package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.CreateModelRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateModelRequest;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredential;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstance;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ModelVO;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/admin/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @GetMapping
    public Mono<Result<List<ModelVO>>> findAll() {
        return Mono.fromCallable(() -> modelService.findAll()
                        .stream().map(ModelVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @GetMapping("/detail")
    public Mono<Result<ModelVO>> findOne(@RequestParam String modelId) {
        return Mono.fromCallable(() -> ModelVO.of(modelService.findByModelId(modelId)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PostMapping
    public Mono<Result<ModelVO>> create(@RequestBody @Valid CreateModelRequest req) {
        return Mono.fromCallable(() -> {
            ModelInstance model = req.toEntity();
            ModelCredential cred = req.toCredential();
            return ModelVO.of(modelService.create(model, cred));
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::ok);
    }

    @PutMapping("/update")
    public Mono<Result<ModelVO>> update(@RequestBody @Valid UpdateModelRequest req) {
        return Mono.fromCallable(() -> ModelVO.of(modelService.update(req.getModelId(), req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @PatchMapping("/enable")
    public Mono<Result<Void>> enable(@RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelService.enable(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PatchMapping("/disable")
    public Mono<Result<Void>> disable(@RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelService.disable(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @DeleteMapping("/delete")
    public Mono<Result<Void>> delete(@RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelService.delete(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @PostMapping("/test")
    public Mono<Result<Map<String, Object>>> test(@RequestParam String modelId) {
        return Mono.fromCallable(() -> {
            modelService.findByModelId(modelId);
            return Map.<String, Object>of(
                    "success", true,
                    "latencyMs", 0,
                    "message", "Model exists (connectivity test not yet implemented)"
            );
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::ok);
    }
}
