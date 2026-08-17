package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;

/**
 * 上游响应字节采集（order=-145）。
 *
 * <p>位置在 QuotaFilter(-160) 之后、ModelRouteFilter(-140) 之前：
 * 此时鉴权与配额都已通过（不必为被拒请求做无谓的装饰），而路由尚未发生（能包住整个转发过程）。
 *
 * <h3>「绝不阻塞正常通讯」的具体保障</h3>
 * 这是本类的硬约束，每一条都对应一个真实的翻车点：
 * <ol>
 *   <li><b>原 DataBuffer 立即向下游转发，绝不缓冲后再发</b>——
 *       攒齐再发会把 SSE 流式变成一次性返回，调用方的打字机效果直接消失。
 *       这里只在数据流过时旁路复制一份字节。</li>
 *   <li><b>复制用只读视图，不移动读指针</b>（{@code readableByteBuffers()}）——
 *       若用消费型读取，下游拿到的就是空 buffer，响应直接损坏。</li>
 *   <li><b>Netty 事件循环线程上只做 append，零解析</b>——
 *       SSE 拼接与 JSON 解析全部推迟到异步消费端；在事件循环里解析大响应会卡住整个 EventLoop
 *       上的所有连接。</li>
 *   <li><b>内存保护阈值</b>——按决策不限制存储大小，但内存缓冲必须有保险丝：
 *       上游异常吐出超大响应时，若无上限会吃光堆内存触发 Full GC / OOM，
 *       那时是全部请求一起挂，恰恰违反"不影响正常通讯"。
 *       达到阈值只停止<b>本条</b>采集并标记截断，转发完全不受影响。</li>
 *   <li><b>采集过程中的任何异常都就地吞掉</b>——报文记录是旁路功能，
 *       绝不允许它把一次正常的业务转发搞失败。</li>
 * </ol>
 *
 * <p>本过滤器只负责「攒字节」，落库与解析见 {@code PayloadService}。
 */
@Slf4j
@Component
public class PayloadCaptureFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -145;

    /** 采集到的响应字节，供 TraceFilter 取用（解析 token）与 PayloadService 落库 */
    public static final String ATTR_RESPONSE_BYTES = "mg.responseBytes";
    /** 响应是否为 SSE 流式，决定后续按哪种结构解析 */
    public static final String ATTR_IS_STREAM = "mg.isStream";
    /** 响应是否因触发内存保护阈值而被截断 */
    public static final String ATTR_RESPONSE_TRUNCATED = "mg.responseTruncated";

    /**
     * 内存保护阈值（字节）。默认 64MB，远超正常报文，正常业务永远碰不到。
     * 设为 0 表示不设防——<b>不建议</b>，等于把稳定性押在上游行为上。
     */
    @Value("${modelgate.payload-log.max-capture-bytes:67108864}")
    private int maxCaptureBytes;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (!path.startsWith(UrlConstant.BUSINESS_PREFIX)) {
            return chain.filter(exchange);
        }
        // 装饰响应，旁路采集字节
        return chain.filter(exchange.mutate().response(decorate(exchange)).build());
    }

    private ServerHttpResponse decorate(ServerWebExchange exchange) {
        ServerHttpResponse original = exchange.getResponse();
        // 每个请求一个独立缓冲区；只在本请求的响应写出期间被单线程访问，无需加锁
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();

        return new ServerHttpResponseDecorator(original) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                // 关键：这里返回的仍是原始 body 流，只是挂了个 doOnNext 旁路复制。
                // 下游 NettyWriteResponseFilter 拿到的每个 buffer 与不装饰时完全一致，
                // 时序也一致 —— 流式响应逐帧到达、逐帧发出，不引入任何缓冲延迟。
                Flux<? extends DataBuffer> flux = Flux.from(body)
                        .doOnNext(dataBuffer -> capture(exchange, buffer, dataBuffer));
                return super.writeWith(flux);
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                // SSE 走的是这个方法（每帧一个内层 Publisher），不覆写会导致流式响应一个字节都采集不到
                return writeWith(Flux.from(body).flatMapSequential(p -> p));
            }
        };
    }

    /**
     * 旁路复制一个 DataBuffer 的内容。
     *
     * <p>任何异常都不得向外抛：本方法运行在响应写出链路上，抛出会直接损坏调用方的响应。
     */
    private void capture(ServerWebExchange exchange, ByteArrayOutputStream buffer, DataBuffer dataBuffer) {
        try {
            if (maxCaptureBytes > 0 && buffer.size() >= maxCaptureBytes) {
                // 已达保护阈值：停止采集但继续转发，只在首次触发时标记与告警
                if (!Boolean.TRUE.equals(exchange.getAttribute(ATTR_RESPONSE_TRUNCATED))) {
                    exchange.getAttributes().put(ATTR_RESPONSE_TRUNCATED, Boolean.TRUE);
                    log.warn("[PayloadCapture] 响应超过内存保护阈值 {} 字节，停止采集（转发不受影响）: traceId={}",
                            maxCaptureBytes, exchange.getAttribute(TraceWebFilter.ATTR_TRACE_ID));
                }
                return;
            }
            // readableByteBuffers() 返回只读视图，不移动 dataBuffer 的读指针，
            // 下游仍能读到完整内容。用消费型 API（如 read(byte[])）会把响应读空。
            try (var iterator = dataBuffer.readableByteBuffers()) {
                while (iterator.hasNext()) {
                    ByteBuffer bb = iterator.next();
                    byte[] chunk = new byte[bb.remaining()];
                    bb.get(chunk);
                    buffer.write(chunk);
                }
            }
            exchange.getAttributes().put(ATTR_RESPONSE_BYTES, buffer);
        } catch (Exception e) {
            // 采集失败只丢这条报文，绝不影响转发
            log.debug("[PayloadCapture] 响应字节采集失败，已跳过: {}", e.getMessage());
        }
    }

    /**
     * 判定响应是否为 SSE 流式。
     *
     * <p>以 Content-Type 为准而非请求体里的 {@code stream:true}：
     * 上游完全可能忽略该参数返回非流式响应，按请求判定会导致解析结构选错、Token 与正文全部取空。
     */
    public static boolean isStreamResponse(ServerWebExchange exchange) {
        MediaType contentType = exchange.getResponse().getHeaders().getContentType();
        return contentType != null && MediaType.TEXT_EVENT_STREAM.isCompatibleWith(contentType);
    }
}
