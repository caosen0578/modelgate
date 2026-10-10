package com.pab.ficc.ibp.modelgate.server.domain.vo;

import java.util.List;
import java.util.Map;

/** 公开能力目录，不含凭证值或可执行页面脚本。 */
public record ProviderAdapterVO(String adapterId, String displayName, int configVersion,
        List<String> modelTypes, List<String> operations, List<String> streamFormats,
        List<String> contentTypes, List<String> authSchemes, List<String> responseModes, List<String> features,
        Map<String, String> defaultPaths, boolean pathEditable, boolean parameterMapping) {
    public ProviderAdapterVO {
        modelTypes = List.copyOf(modelTypes);
        operations = List.copyOf(operations);
        streamFormats = List.copyOf(streamFormats);
        contentTypes = List.copyOf(contentTypes);
        authSchemes = List.copyOf(authSchemes);
        responseModes = List.copyOf(responseModes);
        features = List.copyOf(features);
        defaultPaths = Map.copyOf(defaultPaths);
    }
}
