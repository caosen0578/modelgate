package com.pab.ficc.ibp.modelgate.server.adapter;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

final class ResponseSessions {
    private ResponseSessions() { }
    static ResponseSession inspect(AdapterResponseContext context, ResponseInspector inspector) {
        var type = context.headers().getContentType();
        String encoding = context.headers().getFirst(HttpHeaders.CONTENT_ENCODING);
        if (type == null || encoding != null && !"identity".equalsIgnoreCase(encoding)) return ResponseSession.passthrough();
        if (MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type) || MediaType.APPLICATION_JSON.isCompatibleWith(type))
            return new InspectingResponseSession(inspector, MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type));
        return ResponseSession.passthrough();
    }
}
