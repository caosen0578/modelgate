package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class CreateModelRequestBO {

    @NotBlank private String modelId;
    @NotBlank private String modelName;
    @NotBlank private String provider;
    private String  modelType    = "chat";
    private String  apiSpec      = "openai";
    @NotBlank private String upstreamUrl;
    /** 上游接口路径，如 /v1/chat/completions；留空按 apiSpec 取默认 */
    private String  upstreamPath;
    private Integer timeoutMs    = 30000;
    private String  defaultParams;
    private String  forceParams;
    /** 自定义字段映射 JSON，api_spec=custom 时生效 */
    private String  paramMapping;
    private String  description;
    private CredentialRequestBO credential;

    public ModelInstanceEntity toEntity() {
        var m = new ModelInstanceEntity();
        m.setModelId(modelId);
        m.setModelName(modelName);
        m.setProvider(provider);
        m.setModelType(modelType != null ? modelType : "chat");
        m.setApiSpec(apiSpec != null ? apiSpec : "openai");
        m.setUpstreamUrl(upstreamUrl);
        m.setUpstreamPath(upstreamPath);
        m.setTimeoutMs(timeoutMs != null ? timeoutMs : 30000);
        m.setDefaultParams(defaultParams);
        m.setForceParams(forceParams);
        m.setParamMapping(paramMapping);
        m.setDescription(description);
        m.setEnabled(true);
        return m;
    }

    public ModelCredentialEntity toCredential() {
        if (credential == null) return null;
        var c = new ModelCredentialEntity();
        c.setAuthType(credential.getAuthType());
        c.setAccessKey(credential.getAccessKey());
        c.setSecretKey(credential.getSecretKey());
        c.setExtraConfig(credential.getExtraConfig());
        return c;
    }
}
