package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.domain.base.vo.PageVo;
import com.pab.ficc.ibp.modelgate.server.common.AdminScheduler;
import com.pab.ficc.ibp.modelgate.server.common.PageSupport;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.bo.CreateModelRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.ModelQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.TestConnectionRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.UpdateModelRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ConnectivityTestVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ModelVO;
import com.pab.ficc.ibp.modelgate.server.service.ModelAuthService;
import com.pab.ficc.ibp.modelgate.server.service.ModelConnectivityService;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import jakarta.validation.Valid;

import java.util.Map;

@Slf4j
@Tag(name = "模型管理", description = "注册和管理内网部署的 AI 模型及上游鉴权配置")
@RestController
@RequestMapping(UrlConstant.MG_MODELS)
@RequiredArgsConstructor
public class ModelController {

    private final ModelService modelService;
    private final ModelConnectivityService connectivityService;
    private final ModelAuthService modelAuthService;
    private final ObjectMapper objectMapper;

    @Operation(summary = "分页查询模型（pageIndex 从 1 开始，pageSize 上限 200；keyword 模糊匹配 modelId/modelName）")
    @GetMapping(UrlConstant.ROOT)
    public Mono<Result<PageVo<ModelVO>>> page(ModelQueryBO query) {
        // 分页护栏放在 Mono 外：参数非法应当立刻返回，不必占用管理面线程池的名额
        PageSupport.validate(query, "模型");
        return Mono.fromCallable(() -> {
                    // 一次性取全部凭证与授权计数再归集，避免逐条查询造成 N+1。
                    // 注意：这两张表整表加载，与分页无关——分页只收敛模型主表的返回行数。
                    Map<String, ModelCredentialEntity> credentials = modelService.findAllCredentials();
                    Map<String, Long> authCounts = modelAuthService.countByModelId();
                    PageVo<ModelVO> page = PageVo.of(modelService.page(query),
                            m -> ModelVO.of(m, credentials.get(m.getModelId()), authCounts.get(m.getModelId())));
                    log.debug("[Model][page] pageIndex={}, pageSize={}, total={}",
                            page.getPageIndex(), page.getPageSize(), page.getTotal());
                    return page;
                })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "查询单个模型详情")
    @GetMapping(UrlConstant.DETAIL)
    public Mono<Result<ModelVO>> findOne(@Parameter(description = "模型 ID") @RequestParam("modelId") String modelId) {
        log.debug("[Model][findOne] modelId={}", modelId);
        return Mono.fromCallable(() -> ModelVO.of(modelService.findByModelId(modelId),
                        modelService.getCredential(modelId).orElse(null)))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "新增模型")
    @PostMapping(UrlConstant.ROOT)
    public Mono<Result<ModelVO>> create(@RequestBody @Valid CreateModelRequestBO req) {
        log.info("[Model][create] 收到新增请求: modelId={}, modelName={}, provider={}, apiSpec={}, upstreamUrl={}, authType={}",
                req.getModelId(), req.getModelName(), req.getProvider(), req.getApiSpec(), req.getUpstreamUrl(),
                req.getCredential() != null ? req.getCredential().getAuthType() : "none");
        return Mono.fromCallable(() -> {
                    ModelInstanceEntity model = req.toEntity();
                    ModelCredentialEntity cred = req.toCredential();
                    ModelVO vo = ModelVO.of(modelService.create(model, cred));
                    log.info("[Model][create] 新增成功: modelId={}", vo.getModelId());
                    return vo;
                }).subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "更新模型信息")
    @PutMapping(UrlConstant.UPDATE)
    public Mono<Result<ModelVO>> update(@RequestBody @Valid UpdateModelRequestBO req) {
        log.info("[Model][update] 收到更新请求: modelId={}, upstreamUrl={}, authType={}",
                req.getModelId(), req.getUpstreamUrl(),
                req.getCredential() != null ? req.getCredential().getAuthType() : "未携带");
        return Mono.fromCallable(() -> {
                    String modelId = req.getModelId();
                    ModelInstanceEntity updated = modelService.update(modelId, req.toEntity(), req.toCredential());
                    ModelVO vo = ModelVO.of(updated, modelService.getCredential(modelId).orElse(null));
                    log.info("[Model][update] 更新成功: modelId={}, authType={}", modelId, vo.getAuthType());
                    return vo;
                }).subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "启用模型")
    @PatchMapping(UrlConstant.ENABLE)
    public Mono<Result<Void>> enable(@Parameter(description = "模型 ID") @RequestParam("modelId") String modelId) {
        log.info("[Model][enable] modelId={}", modelId);
        return Mono.fromRunnable(() -> modelService.enable(modelId))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "禁用模型（禁用后请求返回 503）")
    @PatchMapping(UrlConstant.DISABLE)
    public Mono<Result<Void>> disable(@Parameter(description = "模型 ID") @RequestParam("modelId") String modelId) {
        log.info("[Model][disable] modelId={}", modelId);
        return Mono.fromRunnable(() -> modelService.disable(modelId))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "删除模型（存在活跃授权时禁止删除）")
    @DeleteMapping(UrlConstant.DELETE)
    public Mono<Result<Void>> delete(@Parameter(description = "模型 ID") @RequestParam("modelId") String modelId) {
        log.info("[Model][delete] modelId={}", modelId);
        return Mono.fromRunnable(() -> modelService.delete(modelId))
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .then(Mono.just(Result.<Void>success(null)));
    }

    @Operation(summary = "连通性测试（向上游发送真实推理请求，仅上游返回 2xx 判为连通）")
    @PostMapping(UrlConstant.TEST)
    public Mono<Result<ConnectivityTestVO>> test(
            @Parameter(description = "模型 ID；带草稿 body 时可省略") @RequestParam(name = "modelId", required = false) String modelId,
            @Parameter(description = "草稿配置（新增模型时尚未入库），缺省则按 modelId 查库")
            @RequestBody(required = false) TestConnectionRequestBO draft) {

        // 草稿模式：新增模型时记录还没入库，只能用表单里的配置探测
        if (draft != null && draft.hasDraftConfig()) {
            String draftId = draft.getModelId() != null ? draft.getModelId() : modelId;
            draft.setModelId(draftId);
            log.info("[Model][test] 草稿模式探测: modelId={}, apiSpec={}, upstreamUrl={}",
                    draftId, draft.getApiSpec(), draft.getUpstreamUrl());
            return connectivityService.probe(draft.toEntity(), draft.toCredential())
                    .map(Result::success);
        }

        // 已保存模式：按 modelId 查库，SK 需解密后才能用于真实请求
        if (modelId == null || modelId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "modelId 与草稿配置不能同时为空");
        }
        log.info("[Model][test] 查库模式探测: modelId={}", modelId);
        return Mono.fromCallable(() -> {
                    ModelInstanceEntity model = modelService.findByModelId(modelId);
                    ModelCredentialEntity cred = modelService.getCredentialDecrypted(modelId);
                    return new ModelWithCredential(model, cred);
                })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .flatMap(mc -> connectivityService.probe(mc.model(), mc.credential()))
                .map(Result::success);
    }

    @Operation(summary = "对话测试（服务端用库中模型凭证直连上游，密钥不下发浏览器）")
    @PostMapping(UrlConstant.CHAT_TEST)
    public Mono<Result<ConnectivityTestVO>> chatTest(
            @Parameter(description = "模型 ID") @RequestParam("modelId") String modelId,
            @Parameter(description = "OpenAI 规范请求体：messages / temperature / max_tokens 等")
            @RequestBody Map<String, Object> body) {

        if (modelId == null || modelId.isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "modelId 不能为空");
        }
        // 强制以库中 modelId 为准，避免 body 里的 model 与实际测试对象不一致
        body.put("model", modelId);
        log.info("[Model][chatTest] 管理面对话测试: modelId={}, keys={}", modelId, body.keySet());

        return Mono.fromCallable(() -> {
                    ModelInstanceEntity model = modelService.findByModelId(modelId);
                    // 上游密钥在此解密并由适配器注入请求头，全程不出服务端
                    ModelCredentialEntity cred = modelService.getCredentialDecrypted(modelId);
                    return new ModelWithCredential(model, cred);
                })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .flatMap(mc -> {
                    byte[] raw;
                    try {
                        raw = objectMapper.writeValueAsBytes(body);
                    } catch (JsonProcessingException e) {
                        throw new BusinessException(ErrorCode.INVALID_PARAMETER, "请求体序列化失败: " + e.getMessage());
                    }
                    return connectivityService.chat(mc.model(), mc.credential(), raw);
                })
                .map(Result::success);
    }

    private record ModelWithCredential(ModelInstanceEntity model, ModelCredentialEntity credential) {}
}
