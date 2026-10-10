package com.pab.ficc.ibp.modelgate.server.adapter;

/** 操作由客户端路径解析；供应商端点可以使用另一条路径。 */
public enum ProviderOperation {
    CHAT_COMPLETIONS, RESPONSES, EMBEDDINGS, IMAGES, AUDIO, UNKNOWN;

    public static ProviderOperation fromPath(String path) {
        if (path.endsWith("/chat/completions")) return CHAT_COMPLETIONS;
        if (path.endsWith("/responses")) return RESPONSES;
        if (path.endsWith("/embeddings")) return EMBEDDINGS;
        if (path.contains("/images/")) return IMAGES;
        if (path.contains("/audio/")) return AUDIO;
        return UNKNOWN;
    }
}
