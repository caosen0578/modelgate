package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.common.TrafficType;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 请求体缓存过滤器（order=-175）。
 *
 * <p><b>存在原因：</b>WebFlux 的请求体是 Reactive Stream（只能读一次），
 * 而过滤器链中有多个环节需要读取 body（解析 model 字段、参数转换等）。
 * 本过滤器在链路最前端读取并缓存 body 字节数组，
 * 同时通过 {@link ServerHttpRequestDecorator} 将字节数组重新封装回请求流，
 * 使后续过滤器仍可正常读取，解决"只读一次"的问题。
 *
 * <p><b>处理结果：</b>
 * <ul>
 *   <li>{@code exchange.getAttributes().put("mg.bodyBytes", bytes)}：原始字节数组，供后续使用。</li>
 *   <li>{@code exchange.getAttributes().put("mg.modelId", ...)}：从 JSON 的 {@code model}
 *       字段提取，供 ModelAuthFilter、QuotaFilter、TraceFilter 读取，无需再次解析。</li>
 * </ul>
 *
 * <p><b>作用范围：</b>仅对 POST /v1/** 且 Content-Type 为 JSON 的请求生效，
 * GET 请求和管理资源接口直接放行；判断使用去除部署文根后的应用内路径。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BodyParseFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -175;

    /**
     * 请求体里 {@code model} 值的字节起止位置（含前后双引号），供上游模型 ID 替换做定点拼接。
     *
     * <p>只在成功提取到 model 时才写入；提取不到、或 BodyParseFilter 未生效时不存在，
     * 使用方需要有解析回退路径。
     */
    public static final String ATTR_MODEL_VALUE_START = "mg.modelValueStart";
    public static final String ATTR_MODEL_VALUE_END = "mg.modelValueEnd";

    private final ObjectMapper objectMapper;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // 模型专属：路由键在请求体的 model 字段，只有模型流量需要把 body 读全。
        // 服务流量的路由键在 path 上，**绝不能**走到这里——那会白白缓冲整个请求体，
        // 正是新链路比模型链路轻一个量级的原因所在。
        if (TrafficType.resolve(exchange) != TrafficType.MODEL
                || exchange.getRequest().getMethod() != HttpMethod.POST) {
            return chain.filter(exchange);
        }

        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null || !contentType.isCompatibleWith(MediaType.APPLICATION_JSON)) {
            return chain.filter(exchange);
        }

        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    byte[] bytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bytes);
                    DataBufferUtils.release(dataBuffer);

                    exchange.getAttributes().put("mg.bodyBytes", bytes);
                    extractModelId(exchange, bytes);

                    ServerHttpRequestDecorator mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                        @Override
                        public Flux<DataBuffer> getBody() {
                            DataBuffer buf = exchange.getResponse().bufferFactory().wrap(bytes);
                            return Flux.just(buf);
                        }
                    };
                    // Mono<Void> 成功也不发元素，不能直接接 switchIfEmpty，否则正常请求会转发两次。
                    return chain.filter(exchange.mutate().request(mutatedRequest).build()).thenReturn(Boolean.TRUE);
                })
                .switchIfEmpty(Mono.defer(() -> chain.filter(exchange).thenReturn(Boolean.TRUE)))
                .then();
    }

    /**
     * 从请求体里取出顶层的 {@code model} 字段。
     *
     * <p><b>流式扫描，不建树</b>。上一版是
     * {@code new String(bytes, UTF_8)} + {@code objectMapper.readTree(body)}——
     * 为了拿一个字符串字段，先把整个请求体复制成 String，再建一棵完整的 JsonNode 树。
     * 多轮对话的 {@code messages} 数组动辄几百 KB 到几 MB，这两步的分配量与请求体成正比，
     * 而且<b>跑在 Netty 事件循环线程上</b>——请求体越大，卡住的 EventLoop 时间越长，
     * 连累的是该 EventLoop 上的所有连接。
     *
     * <p>现在用 {@link JsonParser} 只遍历<b>顶层字段</b>：遇到对象/数组值直接
     * {@code skipChildren()} 跳过（只做词法扫描，不构造任何节点），扫到 {@code model} 立即返回。
     * 典型请求里 {@code model} 就在最前面，实际只读几十字节就结束。
     *
     * <p>只认顶层的 {@code model}：{@code messages} 里的用户内容完全可能出现同名字段，
     * 建树版本用 {@code node.get("model")} 天然只取顶层，这里必须显式保持同样的语义。
     */
    private void extractModelId(ServerWebExchange exchange, byte[] bytes) {
        try (JsonParser parser = objectMapper.getFactory().createParser(bytes)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) {
                return;   // 顶层不是对象，没有 model 可取
            }
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String name = parser.currentName();
                JsonToken value = parser.nextToken();
                if ("model".equals(name)) {
                    if (value == JsonToken.VALUE_STRING) {
                        exchange.getAttributes().put("mg.modelId", parser.getText());
                        // 顺带记下这个值在原始字节数组里的位置（含前后双引号）。
                        // 配了 upstream_model_id 的模型要把它替换成上游真实 ID，
                        // 有了偏移就能做一次 arraycopy 拼接搞定，**不用再解析一遍整个请求体**。
                        // 这两行是白捡的——parser 此刻就停在该 token 上，取位置不需要额外扫描。
                        long start = parser.currentTokenLocation().getByteOffset();
                        long end = parser.currentLocation().getByteOffset();
                        if (start >= 0 && end > start && end <= bytes.length) {
                            exchange.getAttributes().put(ATTR_MODEL_VALUE_START, (int) start);
                            exchange.getAttributes().put(ATTR_MODEL_VALUE_END, (int) end);
                        }
                    }
                    return;   // 顶层 model 只会有一个，取到即止
                }
                if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    parser.skipChildren();   // 关键：整段跳过，不为 messages 建任何节点
                }
            }
        } catch (Exception e) {
            log.debug("[BodyParse] 请求体未能解析出 model 字段: {}", e.getMessage());
        }
    }
}
