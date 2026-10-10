package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ProviderAdapterVO;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ProviderAdapterRegistry {
    private final Map<String, ProviderAdapter> adapters;
    private final Map<String, ProviderAdapterVO> descriptors;
    private final ObjectMapper mapper;
    public ProviderAdapterRegistry(List<ProviderAdapter> providers, ObjectMapper mapper) {
        this.mapper = mapper;
        var map = new LinkedHashMap<String, ProviderAdapter>();
        var catalog = new LinkedHashMap<String, ProviderAdapterVO>();
        for (var adapter : providers) {
            var descriptor = adapter.descriptor();
            String id = descriptor.adapterId();
            if (id == null || id.isBlank() || !id.equals(adapter.getApiSpec()) || map.putIfAbsent(id, adapter) != null)
                throw new IllegalStateException("ProviderAdapter id 为空、重复或与 descriptor 不一致");
            if (!descriptor.responseModes().contains("PASSTHROUGH"))
                throw new IllegalStateException("ProviderAdapter 必须声明原生响应支持");
            catalog.put(id, descriptor);
        }
        adapters = java.util.Collections.unmodifiableMap(map);
        descriptors = java.util.Collections.unmodifiableMap(catalog);
    }
    public ProviderAdapter resolve(String id) {
        var adapter = adapters.get(id == null || id.isBlank() ? "openai" : id);
        if (adapter == null) throw new BusinessException(ErrorCode.PROVIDER_UNSUPPORTED);
        return adapter;
    }
    public List<ProviderAdapterVO> descriptors() { return List.copyOf(descriptors.values()); }
    public ProviderAdapterVO descriptor(String id) { return descriptors.get(resolve(id).getApiSpec()); }
    public ProviderAdapter validate(ProviderModelConfig config, ProviderCredential credential) {
        var adapter = resolve(config.adapterId());
        var descriptor = descriptors.get(adapter.getApiSpec());
        if (!descriptor.modelTypes().contains(config.modelType())) invalid("modelType 不被该协议支持");
        if (!descriptor.responseModes().contains(config.responseMode().name())) invalid("该协议不支持所选 responseMode");
        if (config.configVersion() != descriptor.configVersion()) invalid("configVersion 不受支持");
        if (!descriptor.authSchemes().contains(credential.authType())) invalid("authType 不被该协议支持");
        try {
            var uri = URI.create(config.upstreamUrl());
            if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getFragment() != null) invalid("upstreamUrl 必须为有效 HTTP(S) 地址");
            if (config.upstreamPath() != null && !config.upstreamPath().isBlank()) {
                var path = URI.create(config.upstreamPath());
                if (!path.getPath().startsWith("/") || path.isAbsolute() || path.getRawAuthority() != null
                    || path.getQuery() != null || path.getFragment() != null) invalid("upstreamPath 必须为绝对路径，不包含 query/fragment");
            }
            if (config.adapterConfig() != null && !config.adapterConfig().isBlank()) {
                var node = mapper.readTree(config.adapterConfig());
                // 当前四适配器没有额外配置字段，禁止把尚未支持的选项/秘密放入明文配置。
                if (!node.isObject() || !node.isEmpty()) invalid("当前适配器 adapterConfig 必须为空 JSON 对象");
            }
        } catch (BusinessException e) { throw e; }
        catch (Exception e) { invalid("上游地址或 adapterConfig 格式非法"); }
        adapter.validate(config, credential);
        return adapter;
    }
    private static void invalid(String message) { throw new BusinessException(ErrorCode.PROVIDER_CONFIG_INVALID, message); }
}
