package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class ModelVO {

    private String        modelId;
    private String        modelName;
    private String        provider;
    private String        modelType;
    private String        apiSpec;
    private String        upstreamUrl;
    private Integer       timeoutMs;
    private String        defaultParams;
    private String        forceParams;
    private String        description;
    private Boolean       enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static ModelVO of(ModelInstanceEntity e) {
        ModelVO vo = new ModelVO();
        vo.modelId      = e.getModelId();
        vo.modelName    = e.getModelName();
        vo.provider     = e.getProvider();
        vo.modelType    = e.getModelType();
        vo.apiSpec      = e.getApiSpec();
        vo.upstreamUrl  = e.getUpstreamUrl();
        vo.timeoutMs    = e.getTimeoutMs();
        vo.defaultParams = e.getDefaultParams();
        vo.forceParams  = e.getForceParams();
        vo.description  = e.getDescription();
        vo.enabled      = e.getEnabled();
        vo.createTime    = e.getCreateTime();
        vo.updateTime    = e.getUpdateTime();
        return vo;
    }
}
