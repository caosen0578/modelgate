package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.adapter.*;
import org.reactivestreams.Publisher;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** 包在 PayloadCapture 外：采集到客户端实际收到的响应；不增加 GlobalFilter/order。 */
final class ProviderResponseDecorator extends ServerHttpResponseDecorator {
    private final ServerWebExchange exchange;
    private final ProviderRequestPipeline.PreparedCall call;
    private ResponseSession session;
    ProviderResponseDecorator(ServerWebExchange exchange, ProviderRequestPipeline.PreparedCall call) {
        super(exchange.getResponse()); this.exchange = exchange; this.call = call;
    }
    private ResponseSession session() {
        if (session == null) {
            var headers = new HttpHeaders(); headers.putAll(getHeaders());
            session = call.adapter().openResponse(new AdapterResponseContext(call.config(), call.operation(),
                getStatusCode() == null ? 200 : getStatusCode().value(), headers));
            session.adaptHeaders(getHeaders());
            if (session.telemetry() != null) exchange.getAttributes().put(ResponseTelemetry.EXCHANGE_ATTRIBUTE, session.telemetry());
        }
        return session;
    }
    @Override public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
        return Mono.defer(() -> {
            var transformed = session().transform(Flux.from(body), bufferFactory());
            MediaType type = getHeaders().getContentType();
            if (type != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(type))
                return super.writeAndFlushWith(transformed.map(Mono::just)).then(Mono.defer(session::finish)).doFinally(signal -> session.close());
            return super.writeWith(transformed).then(Mono.defer(session::finish)).doFinally(signal -> session.close());
        });
    }
    @Override public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
        // 原生透传严格保留已有 flush 分组；转换器产生的新事件各自成为 flush 分组。
        return Mono.defer(() -> {
            if (!session().rewritesBody())
                return super.writeAndFlushWith(Flux.from(body).map(group -> session.transform(Flux.from(group), bufferFactory())))
                    .then(Mono.defer(session::finish)).doFinally(signal -> session.close());
            return writeWith(Flux.from(body).concatMap(Flux::from, 1)
                .doOnDiscard(DataBuffer.class, DataBufferUtils::release));
        });
    }
}
