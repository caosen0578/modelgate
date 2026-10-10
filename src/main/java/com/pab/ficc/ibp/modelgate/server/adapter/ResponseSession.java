package com.pab.ficc.ibp.modelgate.server.adapter;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.http.HttpHeaders;
import reactor.core.publisher.Flux;

/** 每请求创建。消费输入必须 release；透传不得 release；取消时清理半帧，禁止 block/subscribe。 */
public interface ResponseSession {
    default void adaptHeaders(HttpHeaders headers) { }
    default boolean rewritesBody() { return false; }
    Flux<DataBuffer> transform(Flux<DataBuffer> upstream, DataBufferFactory factory);
    default ResponseTelemetry telemetry() { return null; }
    default reactor.core.publisher.Mono<Void> finish() { return reactor.core.publisher.Mono.empty(); }
    default void close() { }
    static ResponseSession passthrough() { return (body, factory) -> body; }
}
