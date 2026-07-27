package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class UpdateModelRequestBO {

    @NotBlank private String modelId;
    @NotBlank private String modelName;
    @NotBlank private String provider;
    private String  modelType;
    private String  apiSpec;
    @NotBlank private String upstreamUrl;
    private Integer timeoutMs;
    private String  defaultParams;
    private String  forceParams;
    private String  description;

    public ModelInstanceEntity toEntity() {
        var m = new ModelInstanceEntity();
        m.setModelName(modelName);
        m.setProvider(provider);
        m.setModelType(modelType);
        m.setApiSpec(apiSpec);
        m.setUpstreamUrl(upstreamUrl);
        m.setTimeoutMs(timeoutMs);
        m.setDefaultParams(defaultParams);
        m.setForceParams(forceParams);
        m.setDescription(description);
        return m;
    }
}
