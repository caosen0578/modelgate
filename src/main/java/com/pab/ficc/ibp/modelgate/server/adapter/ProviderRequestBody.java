package com.pab.ficc.ibp.modelgate.server.adapter;

import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import reactor.core.publisher.Flux;
import java.util.function.Supplier;

/** V2 可重订阅 body；当前入口仅创建 Buffered，未来 multipart/上传由专用路由提供 Streaming。 */
public sealed interface ProviderRequestBody permits ProviderRequestBody.Buffered, ProviderRequestBody.Streaming {
    Flux<DataBuffer> publisher(DataBufferFactory factory);
    long contentLength();
    default byte[] bytes() { throw new BusinessException(ErrorCode.PROVIDER_FEATURE_UNSUPPORTED, "此 body 不支持聚合转换"); }

    record Buffered(byte[] bytes) implements ProviderRequestBody {
        @Override public Flux<DataBuffer> publisher(DataBufferFactory factory) { return Flux.defer(() -> Flux.just(factory.wrap(bytes))); }
        @Override public long contentLength() { return bytes.length; }
        @Override public String toString() { return "Buffered[length=" + bytes.length + "]"; }
    }
    record Streaming(Supplier<? extends Publisher<DataBuffer>> source, long contentLength) implements ProviderRequestBody {
        @Override public Flux<DataBuffer> publisher(DataBufferFactory factory) { return Flux.defer(() -> Flux.from(source.get())); }
        @Override public String toString() { return "Streaming[length=" + contentLength + "]"; }
    }
}
