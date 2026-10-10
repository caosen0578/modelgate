package com.pab.ficc.ibp.modelgate.server.adapter;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;

/** 与持久化对象解耦的不可变协议配置快照。 */
public record ProviderModelConfig(String modelId, String upstreamModelId, String modelType,
        String adapterId, String upstreamUrl, String upstreamPath, ResponseMode responseMode,
        int configVersion, String adapterConfig) {
    public static ProviderModelConfig from(ModelInstanceEntity model) {
        return new ProviderModelConfig(model.getModelId(), model.resolveUpstreamModelId(),
            model.getModelType() == null ? "chat" : model.getModelType(),
            model.getApiSpec() == null ? "openai" : model.getApiSpec(), model.getUpstreamUrl(),
            model.getUpstreamPath(), ResponseMode.resolve(model.getResponseMode()),
            model.getConfigVersion() == null ? 1 : model.getConfigVersion(), model.getAdapterConfig());
    }
    ModelInstanceEntity legacyModel() {
        var model = new ModelInstanceEntity();
        model.setModelId(modelId); model.setUpstreamModelId(upstreamModelId);
        model.setModelType(modelType); model.setApiSpec(adapterId);
        model.setUpstreamUrl(upstreamUrl); model.setUpstreamPath(upstreamPath);
        return model;
    }
}
