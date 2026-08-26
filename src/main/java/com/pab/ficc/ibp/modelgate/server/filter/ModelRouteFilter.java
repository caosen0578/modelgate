package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.adapter.ProviderAdapter;
import com.pab.ficc.ibp.modelgate.server.adapter.RequestParamMerger;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpRequestDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.net.URI;
import java.util.List;
import java.util.Optional;

/**
 * Dynamic model routing filter (order=-140).
 *
 * 1. Reads request body to extract 'model' field
 * 2. Loads ModelInstanceEntity + ModelCredentialEntity from cache
 * 3. Delegates to ProviderAdapter for request transformation
 * 4. Sets GATEWAY_REQUEST_URL_ATTR for upstream routing
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ModelRouteFilter implements GlobalFilter, Ordered {

    private static final int ORDER = -140;

    /**
     * 转发上游前必须剔除的入站请求头——<b>这些头只属于「调用方 → 本网关」这一跳，不属于「本网关 → 上游」</b>。
     *
     * <p><b>为什么必须显式剔除</b>：下方 decorator 的合并逻辑是
     * {@code putAll(调用方原始头)} 再 {@code putAll(适配器注入的上游头)}，
     * 后者<b>只能覆盖同名的头</b>。四种 api_spec 里只有 OpenAI 用的是 {@code Authorization}
     * （{@code setBearerAuth}），Azure 用 {@code api-key}、Anthropic 用 {@code x-api-key}、
     * Custom 的 ak_sk 用 {@code X-Access-Key} —— 这三种情况下调用方那个
     * {@code Authorization: Bearer <ModelGate 凭证>} 不会被覆盖，会<b>原样发给上游模型厂商</b>，
     * 等于把本网关的接入凭证送了出去（上游拿这把 key 可以反过来调本网关）。
     * 全局也没有任何 RemoveRequestHeader 兜底（application.yml 的 default-filters 只有
     * DedupeResponseHeader），所以只能在这里删。
     *
     * <p><b>为什么"透传调用方 Authorization"没有合法用途</b>：调用方带的这把 key 是
     * {@code mg_app_credential} 里的<b>本网关</b>凭证，由 CredentialAuthFilter(-190) 校验，
     * 它对上游本来就没有任何意义。上游要的凭证一律来自 {@code mg_model_credential}，
     * 由适配器注入。所以这里不存在"删了会断掉某种透传模式"的情况。
     *
     * <p><b>X-Mg-* 为什么也要删</b>：这两个是 CredentialAuthFilter 注入的<b>进程内部</b>传参
     * （给 ModelAuthFilter/-170、QuotaFilter/-160 读），三个读取方 order 全部早于本过滤器（-140），
     * 删掉不影响任何下游逻辑；TraceFilter 的 doFinally 取的是 exchange attribute 而非请求头，
     * 同样不受影响。留着只会把内部实现细节泄露给上游。
     *
     * <p>⚠️ 报文留档记录的是<b>入站</b>请求头（PayloadCaptureFilter/-145 早于本过滤器），
     * 排障时仍然能看到调用方带了什么，不受本次剔除影响。
     *
     * <p>注意 {@link HttpHeaders} 的底层是 case-insensitive map，此处写法与调用方大小写无关。
     */
    private static final List<String> INBOUND_ONLY_HEADERS =
            List.of(HttpHeaders.AUTHORIZATION, "X-Mg-App", "X-Mg-Cred-Type");

    private final ModelService modelService;
    private final List<ProviderAdapter> adapters;
    private final ObjectMapper objectMapper;
    /** 模型级 default_params / force_params / param_mapping 的合并器 */
    private final RequestParamMerger paramMerger;

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (path.startsWith(UrlConstant.MANAGE_PREFIX) || path.startsWith(UrlConstant.ACTUATOR_PREFIX)) {
            return chain.filter(exchange);
        }
        // 只处理 /v1/** 的业务流量（chat/completions、embeddings 等）
        if (!path.startsWith(UrlConstant.BUSINESS_PREFIX)) {
            return chain.filter(exchange);
        }

        // BodyParseFilter (-175) 已将请求体读入 mg.bodyBytes；直接复用，避免二次读取（流已消费）
        byte[] cached = exchange.getAttribute("mg.bodyBytes");
        if (cached != null) {
            return processRequest(exchange, chain, cached);
        }
        // 降级路径：mg.bodyBytes 未设置时（理论上不应出现），重新读取 body。
        // 记 WARN 而非静默降级：走到这里说明 BodyParseFilter(-175) 没生效
        // （order 被改动、Content-Type 非 JSON、或请求非 POST），属于配置/链路异常而非调用方问题
        log.warn("[ModelRoute] mg.bodyBytes 缺失，走降级重读请求体路径: path={}, method={}, contentType={}",
                path, exchange.getRequest().getMethod(), exchange.getRequest().getHeaders().getContentType());
        return DataBufferUtils.join(exchange.getRequest().getBody())
                .flatMap(dataBuffer -> {
                    byte[] bodyBytes = new byte[dataBuffer.readableByteCount()];
                    dataBuffer.read(bodyBytes);
                    DataBufferUtils.release(dataBuffer);
                    return processRequest(exchange, chain, bodyBytes);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.info("[ModelRoute] 拒绝: 请求体为空, path={}", path);
                    return reject(exchange, HttpStatus.BAD_REQUEST, ErrorCode.MODEL_FIELD_MISSING);
                }));
    }

    private Mono<Void> processRequest(ServerWebExchange exchange, GatewayFilterChain chain, byte[] bodyBytes) {
        // 快路径：BodyParseFilter(-175) 已经把 model 提取好放进 attribute 了，直接取。
        //
        // 这里原先无条件做 objectMapper.readTree(bodyBytes) —— 把整个请求体建成一棵完整
        // JsonNode 树，而拿到 root 之后**只用它读了一个 model 字段**，读完就丢。
        // 多轮对话的 messages 动辄几百 KB 到几 MB，等于每请求白建一棵大树；
        // 而且 BodyParseFilter 早就用流式扫描把这个值提取过一次，适配器
        // (transformRequestBody) 之后还要为了注入/剔除参数再解析一次 —— 同一份 body
        // 一个请求内被完整解析**三遍**，中间这遍是纯浪费。
        String cachedModelId = exchange.getAttribute("mg.modelId");
        final String modelId;
        if (cachedModelId != null && !cachedModelId.isBlank()) {
            modelId = cachedModelId;
        } else {
            // 回退路径：BodyParseFilter 未生效（order 被改、非 POST、Content-Type 非 JSON），
            // 或请求体里确实没有 model。这时才需要真正解析一次。
            String parsed = parseModelId(bodyBytes);
            if (parsed == null) {
                log.info("[ModelRoute] 拒绝: 请求体非合法 JSON 或缺少 model 字段, bytes={}", bodyBytes.length);
                return reject(exchange, HttpStatus.BAD_REQUEST, ErrorCode.MODEL_FIELD_MISSING);
            }
            modelId = parsed;
            exchange.getAttributes().put("mg.modelId", modelId);
        }

        // 同 ModelAuthFilter：用 Optional 包一层，避免 Callable 返回 null 时 Mono 变 empty、
        // flatMap 不执行导致链路断掉（此处 null 表示"模型不存在或无适配器"，本该走 reject）
        return Mono.fromCallable(() -> Optional.ofNullable(buildRouteInfo(exchange, bodyBytes, modelId)))
        .subscribeOn(Schedulers.boundedElastic())
        .flatMap(result -> {
            RouteInfo info = result.orElse(null);
            if (info == null) {
                return reject(exchange, HttpStatus.NOT_FOUND, ErrorCode.MODEL_NOT_FOUND);
            }
            return applyRoute(exchange, chain, info, modelId);
        });
    }

    /**
     * 回退用：从请求体里解析 model 字段。取不到（非法 JSON / 无该字段 / 值为空）返回 null。
     *
     * <p><b>只在快路径失效时调用</b>，正常流量不会走到这里——正常情况下
     * {@code mg.modelId} 由 BodyParseFilter 的流式扫描提供。
     *
     * <p><b>行为变化说明</b>：改造前这里是无条件执行的，因此"model 字段之后的部分是坏 JSON"
     * 这种请求会被网关直接 400。现在走快路径时不再做整体 JSON 校验，这类请求会被转发到上游、
     * 由上游返回错误。这是有意的取舍：为了拦住罕见的畸形请求，而让<b>每一个正常请求</b>
     * 都付一次完整树解析的代价，不划算；何况上游给出的错误信息比网关的 MODEL_FIELD_MISSING 更准确。
     * "model 字段缺失"这一类仍然会被拦下——那种情况下 BodyParseFilter 提取不到值，
     * 正好落到本回退路径。
     */
    private String parseModelId(byte[] bodyBytes) {
        try {
            JsonNode root = objectMapper.readTree(bodyBytes);
            JsonNode modelNode = root.get("model");
            if (modelNode == null || modelNode.isNull() || modelNode.asText().isBlank()) {
                return null;
            }
            return modelNode.asText();
        } catch (Exception e) {
            log.info("[ModelRoute] 请求体非合法 JSON, bytes={}, cause={}", bodyBytes.length, e.getMessage());
            return null;
        }
    }

    /** 装载模型+凭证、选适配器、构造上游请求；不可用时返回 null（调用方转 404）。 */
    private RouteInfo buildRouteInfo(ServerWebExchange exchange, byte[] bodyBytes, String modelId) {
        {
            Optional<ModelInstanceEntity> modelOpt = modelService.getModelCached(modelId);
            if (modelOpt.isEmpty()) {
                log.info("[ModelRoute] 拒绝: 模型不存在, modelId={}", modelId);
                return null;
            }
            ModelInstanceEntity model = modelOpt.get();

            // 从缓存读取上游鉴权凭证并解密 SK
            ModelCredentialEntity credential = modelService.getCredentialDecrypted(modelId);

            // 按 apiSpec 选择适配器（Strategy 模式）：openai/azure_openai/anthropic/custom
            ProviderAdapter adapter = adapters.stream()
                    .filter(a -> a.supports(model.getApiSpec()))
                    .findFirst()
                    .orElse(null);

            if (adapter == null) {
                // 与"模型不存在"同为 404，但成因完全不同：这是库里 api_spec 填了没人实现的值，
                // 属配置错误而非调用方问题，必须能从日志区分，否则排查会一直往调用方身上找
                log.warn("[ModelRoute] 无可用适配器: modelId={}, apiSpec={}, 已注册={}",
                        modelId, model.getApiSpec(),
                        adapters.stream().map(ProviderAdapter::getApiSpec).toList());
                return null;
            }

            // 适配器负责：请求体参数转换、上游鉴权 Header 构造、上游 URL 构建
            // 先应用模型级参数配置（default_params / force_params / param_mapping），
            // 再交给适配器做 api_spec 特有的改写。顺序不能反：适配器可能会删掉某些字段
            // （如 Azure 会 remove("model")），先跑适配器的话这些配置就落在了会被删掉的字段上。
            // 三项都没配且上游 ID 无需改写时，merge 直接原样返回、不解析不分配；
            // 只配置上游 ID 时则优先走下面的字节偏移定点替换。
            // 把 BodyParseFilter 顺带记下的 model 值偏移传下去：
            // 「只需改上游模型 ID」这种情况能靠它走定点替换，省掉一次完整解析+序列化。
            Integer mvStart = exchange.getAttribute(BodyParseFilter.ATTR_MODEL_VALUE_START);
            Integer mvEnd = exchange.getAttribute(BodyParseFilter.ATTR_MODEL_VALUE_END);
            byte[] mergedBody = paramMerger.merge(bodyBytes, model,
                    mvStart == null ? -1 : mvStart, mvEnd == null ? -1 : mvEnd);
            byte[] transformedBody = adapter.transformRequestBody(mergedBody, model, credential);
            HttpHeaders transformedHeaders = adapter.buildUpstreamHeaders(model, credential);
            URI upstreamUri = adapter.buildUpstreamUri(model, exchange.getRequest().getURI());

            return new RouteInfo(transformedBody, transformedHeaders, upstreamUri);
        }
    }

    private Mono<Void> applyRoute(ServerWebExchange exchange, GatewayFilterChain chain,
                                  RouteInfo info, String modelId) {
        {
            URI upstreamUri = info.upstreamUri();

            // ⚠️ 只设 GATEWAY_REQUEST_URL_ATTR 是不够的：
            // Gateway 内置的 RouteToRequestUrlFilter（order=10000，排在本过滤器 -140 之后）
            // 会用 Route 的**静态 uri** 重新计算并**覆盖**该属性，
            // 结果请求被打到 application.yml 里那个占位地址（${MODEL_UPSTREAM_URL}），
            // 表现为 Connection refused 或 200 空响应。
            // 正确做法是把 Route 本身换掉：RouteToRequestUrlFilter 取 scheme/host/port 自 Route、
            // 取 path/query 自请求 URI，两者都指向上游，合并结果才正确。
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            if (route != null) {
                exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR,
                        Route.async().id(route.getId())
                                .uri(upstreamUri)
                                .order(route.getOrder())
                                .asyncPredicate(route.getPredicate())
                                .filters(route.getFilters())
                                .build());
            }
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, upstreamUri);
            // 转发目标是排查"请求到底打去了哪"的关键信息；DEBUG 级，生产默认关闭
            log.debug("[ModelRoute] 转发: modelId={}, upstream={}", modelId, upstreamUri);

            // 用 ServerHttpRequestDecorator 将新的 body 和 headers 重新注入请求链
            // 必须通过 decorator 而非直接修改，因为 ServerHttpRequest 是不可变对象
            DataBuffer newBuffer = exchange.getResponse().bufferFactory().wrap(info.body());
            ServerHttpRequest mutatedRequest = new ServerHttpRequestDecorator(exchange.getRequest()) {
                @Override
                public URI getURI() {
                    // 让 RouteToRequestUrlFilter 合并出的 path/query 也指向上游
                    // （upstream_path 配了自定义路径时，这一步是必需的）
                    return upstreamUri;
                }

                @Override
                public HttpHeaders getHeaders() {
                    return buildUpstreamHeaders(super.getHeaders(), info.headers(), info.body().length);
                }

                @Override
                public Flux<DataBuffer> getBody() {
                    return Flux.just(newBuffer);
                }
            };

            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        }
    }

    /**
     * 构造发往上游的请求头：调用方原始头 →（剔除本网关凭证与内部头）→ 合并上游凭证 → 修正 Content-Length。
     *
     * <p><b>三步顺序不能调整</b>：剔除必须夹在两次合并之间。放到最前会被
     * {@code putAll(inbound)} 重新带回来；放到最后会把适配器刚注入的上游
     * {@code Authorization}（OpenAI 那条路径）一并删掉，导致上游 401。
     *
     * <p>剔除清单与理由见 {@link #INBOUND_ONLY_HEADERS}。抽成静态方法是为了让这条安全属性
     * 能被 {@code ModelRouteHeaderTest} 直接覆盖——原先它埋在匿名 decorator 里，
     * 不起一整套 exchange 就没法验证。
     *
     * @param inbound        调用方原始请求头（只读不改）
     * @param upstreamAuth   适配器按 api_spec 构造的上游鉴权头
     * @param contentLength  改写后的请求体长度
     */
    static HttpHeaders buildUpstreamHeaders(HttpHeaders inbound, HttpHeaders upstreamAuth, int contentLength) {
        HttpHeaders merged = new HttpHeaders();
        merged.putAll(inbound);
        INBOUND_ONLY_HEADERS.forEach(merged::remove);
        merged.putAll(upstreamAuth);
        merged.setContentLength(contentLength); // 更新 Content-Length，避免上游解析失败
        return merged;
    }

    private Mono<Void> reject(ServerWebExchange exchange, HttpStatus status, ErrorCode errorCode) {
        var response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] bytes;
        try {
            bytes = objectMapper.writeValueAsBytes(Result.fail(errorCode));
        } catch (JsonProcessingException e) {
            bytes = ("{\"code\":" + errorCode.getCode() + ",\"message\":\"" + errorCode.getMessage() + "\"}").getBytes();
        }
        DataBuffer buffer = response.bufferFactory().wrap(bytes);
        return response.writeWith(Mono.just(buffer));
    }

    private record RouteInfo(byte[] body, HttpHeaders headers, URI upstreamUri) {}
}
