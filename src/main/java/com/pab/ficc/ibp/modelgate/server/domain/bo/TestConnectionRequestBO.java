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
    private String responseMode;
    private Integer configVersion;
    private String adapterConfig;
    private String upstreamUrl;
    private String upstreamPath;
    private Integer timeoutMs;
    /** 上游供应商识别的真实模型 ID；留空时与对外 modelId 相同 */
    private String upstreamModelId;
    /** 草稿测试也必须验证这两项参数配置，不能出现“测试通过、保存后行为变化” */
    private String defaultParams;
    private String forceParams;
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
        m.setResponseMode(responseMode);
        m.setConfigVersion(configVersion);
        m.setAdapterConfig(adapterConfig);
        m.setUpstreamUrl(upstreamUrl);
        m.setUpstreamPath(upstreamPath);
        m.setTimeoutMs(timeoutMs);
        m.setUpstreamModelId(upstreamModelId);
        m.setDefaultParams(defaultParams);
        m.setForceParams(forceParams);
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

    /**
     * 生成编辑草稿的探测凭证，并在页面未重填密钥时复用已保存值。
     *
     * <p>只复用凭证字段，模型地址、上游真实 ID 和参数配置始终来自当前草稿；否则用户编辑这些字段后
     * 点击“测试连通”，实际测到的却仍是数据库旧配置。返回新对象，不修改 Caffeine 中的已解密凭证实例。
     */
    public ModelCredentialEntity toCredential(ModelCredentialEntity saved) {
        if (credential == null && saved == null) {
            return null;
        }
        ModelCredentialEntity result = toCredential();
        if (result == null) {
            result = copyCredential(saved);
        }
        // 编辑时 query 可能是旧 ID、草稿里已填新 ID；探测凭证应归属于当前草稿模型。
        result.setModelId(modelId);
        if ("none".equals(result.getAuthType())) {
            // 与 ModelService 更新语义一致：不鉴权就不能携带历史 AK/SK。
            result.setAccessKey(null);
            result.setSecretKey(null);
            return result;
        }
        if (saved != null) {
            if (isBlank(result.getAuthType())) result.setAuthType(saved.getAuthType());
            if (isBlank(result.getAccessKey())) result.setAccessKey(saved.getAccessKey());
            if (isBlank(result.getSecretKey())) result.setSecretKey(saved.getSecretKey());
            if (isBlank(result.getExtraConfig())) result.setExtraConfig(saved.getExtraConfig());
        }
        return result;
    }

    /** 是否需要查询已保存凭证来补齐编辑页不会回显的字段。 */
    public boolean requiresSavedCredential() {
        if (credential == null) return true;
        String authType = credential.getAuthType();
        if ("none".equals(authType)) return false;
        if (isBlank(authType) || isBlank(credential.getSecretKey())) return true;
        if ("ak_sk".equals(authType) && isBlank(credential.getAccessKey())) return true;
        return "azure".equals(authType) && isBlank(credential.getExtraConfig());
    }

    private ModelCredentialEntity copyCredential(ModelCredentialEntity source) {
        if (source == null) return null;
        ModelCredentialEntity copy = new ModelCredentialEntity();
        copy.setModelId(source.getModelId());
        copy.setAuthType(source.getAuthType());
        copy.setAccessKey(source.getAccessKey());
        copy.setSecretKey(source.getSecretKey());
        copy.setExtraConfig(source.getExtraConfig());
        return copy;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
