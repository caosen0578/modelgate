package com.pab.ficc.ibp.modelgate.server.adapter;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import org.springframework.http.HttpHeaders;

import java.net.URI;

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

    default boolean supports(String apiSpec) {
        return getApiSpec().equals(apiSpec);
    }

    /**
     * Transforms the raw request body bytes before forwarding to upstream.
     * Return the original bytes unchanged if no transformation is needed.
     */
    byte[] transformRequestBody(byte[] originalBody, ModelInstanceEntity model, ModelCredentialEntity credential);

    /**
     * Builds the upstream authorization and protocol headers.
     */
    HttpHeaders buildUpstreamHeaders(ModelInstanceEntity model, ModelCredentialEntity credential);

    /**
     * Constructs the target upstream URI from the model config and original request URI.
     */
    URI buildUpstreamUri(ModelInstanceEntity model, URI originalUri);

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
