package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import lombok.Data;

/**
 * 连通性测试的草稿配置（可选 body）。
 *
 * <p>新增模型时记录尚未入库，只有 modelId 无法拿到上游地址与密钥，因此允许把表单里的配置
 * 直接带过来测；body 缺省时退回按 {@code modelId} 查库测已保存的模型。
 *
 * <p>此处 {@code credential.secretKey} 是**明文**（前端表单原样提交），不落库、不入日志，
 * 仅用于本次探测请求。
 */
@Data
public class TestConnectionRequestBO {

    private String modelId;
    private String modelType;
    private String apiSpec;
    private String upstreamUrl;
    private String upstreamPath;
    private Integer timeoutMs;
    private String paramMapping;
    private CredentialRequestBO credential;

    /** 草稿是否可用于探测：至少要有上游地址 */
    public boolean hasDraftConfig() {
        return upstreamUrl != null && !upstreamUrl.isBlank();
    }

    public ModelInstanceEntity toEntity() {
        ModelInstanceEntity m = new ModelInstanceEntity();
        m.setModelId(modelId);
        m.setModelType(modelType != null ? modelType : "chat");
        m.setApiSpec(apiSpec != null ? apiSpec : "openai");
        m.setUpstreamUrl(upstreamUrl);
        m.setUpstreamPath(upstreamPath);
        m.setTimeoutMs(timeoutMs);
        m.setParamMapping(paramMapping);
        return m;
    }

    /** 草稿凭证；密钥保持明文（未入库，无需解密） */
    public ModelCredentialEntity toCredential() {
        if (credential == null) {
            return null;
        }
        ModelCredentialEntity c = new ModelCredentialEntity();
        c.setModelId(modelId);
        c.setAuthType(credential.getAuthType());
        c.setAccessKey(credential.getAccessKey());
        c.setSecretKey(credential.getSecretKey());
        c.setExtraConfig(credential.getExtraConfig());
        return c;
    }
}
