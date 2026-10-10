package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class UpdateModelRequestBO {

    @NotBlank private String modelId;
    @NotBlank private String modelName;
    @NotBlank private String provider;
    private String  modelType;
    private String  apiSpec;
    private String responseMode;
    private Integer configVersion;
    private String adapterConfig;
    @NotBlank private String upstreamUrl;
    /** 上游接口路径，如 /v1/chat/completions；留空按 apiSpec 取默认 */
    private String  upstreamPath;
    private Integer timeoutMs;
    /**
     * 新的对外模型 ID。留空 = 不改名。
     *
     * <p>{@code modelId} 始终是<b>定位用的原 ID</b>，本字段才是要改成的目标值——
     * 两者分开是因为「改名」必须同时知道改前和改后，用一个字段表达不了。
     *
     * <p>⚠️ 改名会让仍在用旧 ID 的接入方在缓存收敛后 404，且历史审计保留旧 ID。
     * 详见 {@code ModelService.update} 的说明。
     */
    private String  newModelId;

    /** 上游真实模型 ID，留空则透传 modelId */
    private String  upstreamModelId;
    private String  defaultParams;
    private String  forceParams;
    /** 自定义字段映射 JSON，api_spec=custom 时生效 */
    private String  paramMapping;
    private String  description;
    /**
     * 上游鉴权配置。缺省表示"本次不修改凭证"；
     * 传了则按 authType 落库（secretKey 留空表示沿用库中原密钥，见 ModelService#update）。
     */
    private CredentialRequestBO credential;

    public ModelInstanceEntity toEntity() {
        var m = new ModelInstanceEntity();
        m.setModelName(modelName);
        m.setProvider(provider);
        m.setModelType(modelType);
        m.setApiSpec(apiSpec);
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
        m.setDescription(description);
        return m;
    }

    /** 凭证更新请求；secretKey 保持明文，由 ModelService 决定加密还是沿用原值 */
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
