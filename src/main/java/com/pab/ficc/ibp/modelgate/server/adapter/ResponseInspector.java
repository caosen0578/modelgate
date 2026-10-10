package com.pab.ficc.ibp.modelgate.server.adapter;

/** 协议遥测可独立复用在原生透传和标准化响应中。 */
public interface ResponseInspector {
    void inspectJson(byte[] json);
    void inspectEvent(SseFrameDecoder.Frame event);
    ResponseTelemetry telemetry();
}
