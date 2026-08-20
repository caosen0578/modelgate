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
    @NotBlank private String upstreamUrl;
    /** 上游接口路径，如 /v1/chat/completions；留空按 apiSpec 取默认 */
    private String  upstreamPath;
    private Integer timeoutMs;
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
