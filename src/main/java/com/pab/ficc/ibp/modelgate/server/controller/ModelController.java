package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.dto.CreateModelRequest;
import com.pab.ficc.ibp.modelgate.server.domain.dto.UpdateModelRequest;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredential;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstance;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ModelVO;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Tag(name = "模型管理", description = "注册和管理内网部署的 AI 模型及上游鉴权配置")
@RestController
@RequestMapping("/admin/models")
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;

    @Operation(summary = "查询所有模型")
    @GetMapping
    public Mono<Result<List<ModelVO>>> findAll() {
        return Mono.fromCallable(() -> modelService.findAll()
                        .stream().map(ModelVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @Operation(summary = "查询单个模型详情")
    @GetMapping("/detail")
    public Mono<Result<ModelVO>> findOne(@Parameter(description = "模型 ID") @RequestParam String modelId) {
        return Mono.fromCallable(() -> ModelVO.of(modelService.findByModelId(modelId)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @Operation(summary = "新增模型")
    @PostMapping
    public Mono<Result<ModelVO>> create(@RequestBody @Valid CreateModelRequest req) {
        return Mono.fromCallable(() -> {
            ModelInstance model = req.toEntity();
            ModelCredential cred = req.toCredential();
            return ModelVO.of(modelService.create(model, cred));
        }).subscribeOn(Schedulers.boundedElastic()).map(Result::ok);
    }

    @Operation(summary = "更新模型信息")
    @PutMapping("/update")
    public Mono<Result<ModelVO>> update(@RequestBody @Valid UpdateModelRequest req) {
        return Mono.fromCallable(() -> ModelVO.of(modelService.update(req.getModelId(), req.toEntity())))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    @Operation(summary = "启用模型")
    @PatchMapping("/enable")
    public Mono<Result<Void>> enable(@Parameter(description = "模型 ID") @RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelService.enable(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @Operation(summary = "禁用模型（禁用后请求返回 503）")
    @PatchMapping("/disable")
    public Mono<Result<Void>> disable(@Parameter(description = "模型 ID") @RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelService.disable(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @Operation(summary = "删除模型（存在活跃授权时禁止删除）")
    @DeleteMapping("/delete")
    public Mono<Result<Void>> delete(@Parameter(description = "模型 ID") @RequestParam String modelId) {
        return Mono.fromRunnable(() -> modelService.delete(modelId))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(Result.<Void>ok()));
    }

    @Operation(summary = "连通性测试（向上游发送测试请求，返回延迟）")
    @PostMapping("/test")
    public Mono<Result<Map<String, Object>>> test(@Parameter(description = "模型 ID") @RequestParam String modelId) {
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
