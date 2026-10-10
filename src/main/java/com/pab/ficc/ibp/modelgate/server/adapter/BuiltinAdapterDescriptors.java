package com.pab.ficc.ibp.modelgate.server.adapter;

import com.pab.ficc.ibp.modelgate.server.domain.vo.ProviderAdapterVO;
import java.util.List;
import java.util.Map;

final class BuiltinAdapterDescriptors {
    private BuiltinAdapterDescriptors() { }
    static ProviderAdapterVO descriptor(String id) {
        if (!List.of("openai", "azure_openai", "anthropic", "custom").contains(id))
            throw new IllegalStateException("新增 ProviderAdapter 必须声明 descriptor: " + id);
        boolean anthropic = "anthropic".equals(id);
        boolean custom = "custom".equals(id);
        return new ProviderAdapterVO(id, switch (id) {
            case "openai" -> "OpenAI 兼容";
            case "azure_openai" -> "Azure OpenAI";
            case "anthropic" -> "Anthropic Messages";
            default -> "自定义协议（原生透传）";
        }, 1, anthropic ? List.of("chat") : List.of("chat", "embedding", "image", "audio"),
            anthropic ? List.of("CHAT_COMPLETIONS") : List.of("CHAT_COMPLETIONS", "RESPONSES", "EMBEDDINGS", "IMAGES", "AUDIO", "UNKNOWN"),
            List.of("JSON", "SSE"), List.of("application/json"),
            custom ? List.of("api_key", "ak_sk", "none") : "azure_openai".equals(id)
                    ? List.of("azure", "api_key", "none") : List.of("api_key", "none"),
            custom ? List.of("PASSTHROUGH") : List.of("PASSTHROUGH", "OPENAI_COMPATIBLE"),
            anthropic ? List.of("TEXT", "TOOLS", "VISION", "MULTIPLE_SYSTEM_MESSAGES", "STOP_SEQUENCES")
                : custom ? List.of("REQUEST_PARAMETER_MAPPING", "NATIVE_RESPONSE") : List.of("REQUEST_PASSTHROUGH"),
            Map.of("chat", anthropic ? "/v1/messages" : "/v1/chat/completions", "embedding", "/v1/embeddings",
                "image", "/v1/images/generations", "audio", "/v1/audio/speech"),
            !"azure_openai".equals(id), custom);
    }
}
