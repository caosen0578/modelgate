package com.pab.ficc.ibp.modelgate.server.adapter;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import java.io.ByteArrayOutputStream;

/** 原始 buffer/flush 不变，旁路协议解析在 CPU scheduler；排队 prefetch=1。 */
final class InspectingResponseSession implements ResponseSession {
    private static final int MAX_JSON_BYTES = 8 * 1024 * 1024;
    private final ResponseInspector inspector;
    private final boolean stream;
    private final SseFrameDecoder decoder = new SseFrameDecoder(1024 * 1024);
    private final ByteArrayOutputStream json = new ByteArrayOutputStream();
    private volatile boolean inspectionDisabled;
    private record ObservedChunk(DataBuffer buffer, byte[] bytes) { }
    InspectingResponseSession(ResponseInspector inspector, boolean stream) { this.inspector = inspector; this.stream = stream; }
    @Override public ResponseTelemetry telemetry() { return inspector.telemetry(); }
    @Override public Flux<DataBuffer> transform(Flux<DataBuffer> upstream, DataBufferFactory factory) {
        return upstream.map(buffer -> {
            byte[] bytes = inspectionDisabled ? new byte[0] : new byte[buffer.readableByteCount()];
            if (!inspectionDisabled) buffer.toByteBuffer().get(bytes); // 不移动原始 DataBuffer 读指针。
            return new ObservedChunk(buffer, bytes);
        }).publishOn(Schedulers.parallel(), 1).map(chunk -> {
            if (!inspectionDisabled) {
                try {
                    if (stream) for (var event : decoder.accept(chunk.bytes())) inspector.inspectEvent(event);
                    else if ((long) json.size() + chunk.bytes().length <= MAX_JSON_BYTES) json.writeBytes(chunk.bytes());
                    else { inspectionDisabled = true; json.reset(); }
                } catch (Exception ignored) { inspectionDisabled = true; decoder.clear(); }
            }
            return chunk.buffer();
        }).doOnDiscard(ObservedChunk.class, chunk -> DataBufferUtils.release(chunk.buffer()))
            .doOnDiscard(DataBuffer.class, DataBufferUtils::release);
    }
    @Override public Mono<Void> finish() {
        if (stream || inspectionDisabled) return Mono.empty();
        return Mono.<Void>fromRunnable(() -> inspector.inspectJson(json.toByteArray())).subscribeOn(Schedulers.parallel());
    }
    @Override public void close() { decoder.clear(); json.reset(); }
}
