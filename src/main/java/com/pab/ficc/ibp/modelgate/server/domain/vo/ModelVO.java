package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
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
    private String        responseMode;
    private Integer       configVersion;
    private String        adapterConfig;
    private String        upstreamUrl;
    /** 上游接口路径；留空表示按 apiSpec 取默认，前端据此展示占位提示 */
    private String        upstreamPath;
    private Integer       timeoutMs;
    /** 上游真实模型 ID，留空表示透传 modelId */
    private String        upstreamModelId;
    private String        defaultParams;
    private String        forceParams;
    /** 自定义字段映射 JSON，api_spec=custom 时生效；编辑弹窗需要回显 */
    private String        paramMapping;
    private String        description;
    private Boolean       enabled;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    /**
     * 授权了本模型的接入系统数量（授权记录数，不区分是否实际生效）。
     * 列表页展示用；具体是哪些系统、是否生效见 {@code GET /auth/apps}。
     */
    private Long          authorizedAppCount;

    /** 上游鉴权类型；编辑弹窗需要回显，否则永远显示默认的 api_key */
    private String        authType;
    /** 上游 AK（非机密）；secretKey 属机密，**任何情况下都不下发** */
    private String        accessKey;
    /** 库中是否已存有密钥，用于前端提示"留空则沿用原密钥" */
    private Boolean       secretKeyConfigured;

    public static ModelVO of(ModelInstanceEntity e) {
        ModelVO vo = new ModelVO();
        vo.modelId      = e.getModelId();
        vo.modelName    = e.getModelName();
        vo.provider     = e.getProvider();
        vo.modelType    = e.getModelType();
        vo.apiSpec      = e.getApiSpec();
        vo.responseMode = e.getResponseMode() == null ? "PASSTHROUGH" : e.getResponseMode();
        vo.configVersion = e.getConfigVersion() == null ? 1 : e.getConfigVersion();
        vo.adapterConfig = e.getAdapterConfig();
        vo.upstreamUrl  = e.getUpstreamUrl();
        vo.upstreamPath = e.getUpstreamPath();
        vo.timeoutMs    = e.getTimeoutMs();
        vo.upstreamModelId = e.getUpstreamModelId();
        vo.defaultParams = e.getDefaultParams();
        vo.forceParams  = e.getForceParams();
        vo.paramMapping = e.getParamMapping();
        vo.description  = e.getDescription();
        vo.enabled      = e.getEnabled();
        vo.createTime    = e.getCreateTime();
        vo.updateTime    = e.getUpdateTime();
        return vo;
    }

    /** 带凭证的转换；credential 为 null 时鉴权相关字段留空 */
    public static ModelVO of(ModelInstanceEntity e, ModelCredentialEntity c) {
        ModelVO vo = of(e);
        if (c != null) {
            vo.authType  = c.getAuthType();
            vo.accessKey = c.getAccessKey();
            vo.secretKeyConfigured = c.getSecretKey() != null && !c.getSecretKey().isBlank();
        }
        return vo;
    }

    /** 列表页用：额外带上授权系统数量；count 为 null 时归零而非留空，前端不必再兜底 */
    public static ModelVO of(ModelInstanceEntity e, ModelCredentialEntity c, Long authorizedAppCount) {
        ModelVO vo = of(e, c);
        vo.authorizedAppCount = authorizedAppCount != null ? authorizedAppCount : 0L;
        return vo;
    }
}
