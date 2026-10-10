package com.pab.ficc.ibp.modelgate.server.adapter;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import org.springframework.http.HttpHeaders;

import java.net.URI;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ProviderAdapterVO;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import org.springframework.http.HttpMethod;
import reactor.core.publisher.Mono;

/**
 * Strategy interface for upstream model provider protocol adaptation.
 */
public interface ProviderAdapter {

    /** 上游鉴权类型：不鉴权 */
    String AUTH_TYPE_NONE = "none";

    /**
     * 判断是否应当为该凭证附加上游鉴权头。
     *
     * <p>{@code authType=none} 表示明确不鉴权，此时即使库中残留 secretKey 也**不得**发送，
     * 否则管理端把鉴权方式改成"不鉴权"在转发链路上不会生效。
     */
    static boolean shouldAuthenticate(ModelCredentialEntity credential) {
        return credential != null
                && !AUTH_TYPE_NONE.equals(credential.getAuthType())
                && credential.getSecretKey() != null;
    }

    /**
     * Returns the apiSpec value this adapter handles (e.g., "openai", "azure_openai", "anthropic", "custom").
     */
    String getApiSpec();

    default ProviderAdapterVO descriptor() { return BuiltinAdapterDescriptors.descriptor(getApiSpec()); }

    /** V2 默认桥：先生成最终 URI/body/header，再调用异步认证 SPI。 */
    default Mono<PreparedUpstreamRequest> prepare(AdapterRequestContext context) {
        return Mono.defer(() -> {
            var model = context.model().legacyModel();
            var credential = context.credential().legacyCredential();
            byte[] body = transformRequestBody(context.body(), model, credential);
            URI uri = buildUpstreamUri(model, context.originalUri());
            HttpHeaders headers = ProviderHeaderPolicy.sanitize(context.headers());
            headers.putAll(buildUpstreamHeaders(model, null));
            // model-route 保留 PreserveHostHeader；其值必须是最终上游 authority，签名也基于同一值。
            headers.set(HttpHeaders.HOST, uri.getRawAuthority());
            headers.setContentLength(body.length);
            return authProvider(context).authenticate(
                new PreparedUpstreamRequest(context.method(), uri, headers, body), context.credential());
        });
    }

    default AuthProvider authProvider(AdapterRequestContext context) {
        return (request, credential) -> Mono.fromSupplier(() -> {
            var headers = new HttpHeaders(); headers.putAll(request.headers());
            headers.putAll(buildUpstreamHeaders(context.model().legacyModel(), credential.legacyCredential()));
            return new PreparedUpstreamRequest(request.method(), request.uri(), headers, request.payload());
        });
    }

    default void validate(ProviderModelConfig model, ProviderCredential credential) { }

    default ResponseSession openResponse(AdapterResponseContext context) { return ResponseSession.passthrough(); }

    default ProbePlan createProbe(ProviderModelConfig model, ObjectMapper mapper) {
        var body = mapper.createObjectNode().put("model", model.modelId());
        if ("embedding".equals(model.modelType())) {
            body.put("input", "ping");
            return new ProbePlan(HttpMethod.POST, URI.create("/v1/embeddings"), body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        if (!"chat".equals(model.modelType()))
            throw new BusinessException(ErrorCode.PROVIDER_FEATURE_UNSUPPORTED, "该操作没有无副作用的最小探测体");
        body.putArray("messages").addObject().put("role", "user").put("content", "ping");
        body.put("max_tokens", 1).put("stream", false);
        return new ProbePlan(HttpMethod.POST, URI.create("/v1/chat/completions"), body.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    default boolean supports(String apiSpec) {
        return getApiSpec().equals(apiSpec);
    }

    /**
     * Transforms the raw request body bytes before forwarding to upstream.
     * Return the original bytes unchanged if no transformation is needed.
     */
    @Deprecated
    default byte[] transformRequestBody(byte[] originalBody, ModelInstanceEntity model, ModelCredentialEntity credential) {
        throw new BusinessException(ErrorCode.PROVIDER_FEATURE_UNSUPPORTED, "请使用 V2 prepare 接口");
    }

    /**
     * Builds the upstream authorization and protocol headers.
     */
    @Deprecated
    default HttpHeaders buildUpstreamHeaders(ModelInstanceEntity model, ModelCredentialEntity credential) {
        throw new BusinessException(ErrorCode.PROVIDER_FEATURE_UNSUPPORTED, "请使用 V2 prepare 接口");
    }

    /**
     * Constructs the target upstream URI from the model config and original request URI.
     */
    @Deprecated
    default URI buildUpstreamUri(ModelInstanceEntity model, URI originalUri) {
        throw new BusinessException(ErrorCode.PROVIDER_FEATURE_UNSUPPORTED, "请使用 V2 prepare 接口");
    }

    /**
     * 解析上游接口路径：模型配了 {@code upstream_path} 就用它，否则回落到该规范的默认路径。
     *
     * <p>此前路径完全写死在各适配器里（openai/custom 透传调用方路径、anthropic 固定
     * {@code /v1/messages}），要求上游必须恰好监听同样的路径。上游若是 {@code /api/generate}
     * 这类自定义端点，真实转发会 404。加配置项后留空即维持原行为，存量模型不受影响。
     *
     * @param configured 模型配置的 upstream_path，可为 null/空
     * @param fallback   该规范的默认路径
     */
    default String resolvePath(String configured, String fallback) {
        return (configured != null && !configured.isBlank()) ? configured.trim() : fallback;
    }
}
