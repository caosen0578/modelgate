package com.pab.ficc.ibp.modelgate.server.filter;

import org.springframework.http.HttpHeaders;

import java.util.List;

/**
 * 构造发往上游的请求头：调用方原始头 →（剔除本网关凭证与内部头）→ 合并上游凭证。
 *
 * <p>模型链路（{@code ModelRouteFilter}）与服务链路（{@code ServiceRouteFilter}）共用。
 * 两条链路的上游凭证来源不同（前者来自 {@code mg_model_credential} 并经适配器按
 * {@code api_spec} 构造，后者来自 {@code mg_service_credential}），
 * 但<b>"不得把调用方凭证转发出去"这条安全属性是共同的</b>，必须只有一份实现。
 */
public final class UpstreamHeaderBuilder {

    private UpstreamHeaderBuilder() {
    }

    /**
     * 转发上游前必须剔除的入站请求头——<b>这些头只属于「调用方 → 本网关」这一跳，不属于「本网关 → 上游」</b>。
     *
     * <p><b>为什么必须显式剔除</b>：合并逻辑是
     * {@code putAll(调用方原始头)} 再 {@code putAll(上游凭证头)}，
     * 后者<b>只能覆盖同名的头</b>。模型侧四种 api_spec 里只有 OpenAI 用的是
     * {@code Authorization}（{@code setBearerAuth}），Azure 用 {@code api-key}、
     * Anthropic 用 {@code x-api-key}、Custom 的 ak_sk 用 {@code X-Access-Key} ——
     * 这三种情况下调用方那个 {@code Authorization: Bearer <ModelGate 凭证>} 不会被覆盖，
     * 会<b>原样发给上游模型厂商</b>，等于把本网关的接入凭证送了出去
     * （上游拿这把 key 可以反过来调本网关）。
     * 全局也没有任何 RemoveRequestHeader 兜底（application.yml 的 default-filters 只有
     * DedupeResponseHeader），所以只能在这里删。
     *
     * <p>服务侧更需要这一条：{@code mg_service_credential.auth_type} 有 {@code none} 与
     * {@code custom_header} 两种<b>根本不写 {@code Authorization}</b> 的取值，
     * 不显式剔除的话网关凭证会直接散给所有接入的上游系统。
     *
     * <p><b>为什么"透传调用方 Authorization"没有合法用途</b>：调用方带的这把 key 是
     * {@code mg_app_credential} 里的<b>本网关</b>凭证，由 CredentialAuthFilter(-190) 校验，
     * 它对上游本来就没有意义。上游要的凭证一律来自各自的凭证表。
     * 所以这里不存在"删了会打断某种透传模式"的情况。
     *
     * <p><b>X-Mg-* 为什么也要删</b>：这两个是 CredentialAuthFilter 注入的<b>进程内部</b>传参
     * （给 ModelAuthFilter/-170、QuotaFilter/-160 读），读取方 order 全部早于路由过滤器（-140），
     * 删掉不影响任何下游逻辑；TraceFilter 的 doFinally 取的是 exchange attribute 而非请求头，
     * 同样不受影响。留着只会把内部实现细节泄露给上游。
     *
     * <p>⚠️ 报文留档记录的是<b>入站</b>请求头（PayloadCaptureFilter/-145 早于路由过滤器），
     * 排障时仍然能看到调用方带了什么，不受本次剔除影响。
     *
     * <p>注意 {@link HttpHeaders} 的底层是 case-insensitive map，此处写法与调用方大小写无关。
     */
    static final List<String> INBOUND_ONLY_HEADERS =
            List.of(HttpHeaders.AUTHORIZATION, "X-Mg-App", "X-Mg-Cred-Type");

    /**
     * 模型链路用：请求体被适配器改写过，需要同步修正 {@code Content-Length}。
     *
     * <p><b>三步顺序不能调整</b>：剔除必须夹在两次合并之间。放到最前会被
     * {@code putAll(inbound)} 重新带回来；放到最后会把适配器刚注入的上游
     * {@code Authorization}（OpenAI 那条路径）一并删掉，导致上游 401。
     *
     * @param inbound       调用方原始请求头（只读不改）
     * @param upstreamAuth  上游鉴权头（模型侧由适配器按 api_spec 构造）
     * @param contentLength 改写后的请求体长度
     */
    public static HttpHeaders build(HttpHeaders inbound, HttpHeaders upstreamAuth, int contentLength) {
        HttpHeaders merged = strip(inbound);
        merged.putAll(upstreamAuth);
        merged.setContentLength(contentLength); // 更新 Content-Length，避免上游解析失败
        return merged;
    }

    /**
     * 服务链路用：请求体<b>原样流式透传、没有被改写</b>，因此绝不能碰 {@code Content-Length}。
     *
     * <p>这正是服务链路比模型链路轻的关键——不缓冲、不解析、不重新计算长度。
     * 若这里跟着模型侧去 {@code setContentLength}，就必须先知道请求体有多长，
     * 而那意味着要把整个 body 读进内存，把好不容易省下的开销又付回去。
     * 调用方原始的 {@code Content-Length} 或 {@code Transfer-Encoding: chunked}
     * 由 {@link #strip} 原样保留，直接透传即可。
     */
    public static HttpHeaders build(HttpHeaders inbound, HttpHeaders upstreamAuth) {
        HttpHeaders merged = strip(inbound);
        merged.putAll(upstreamAuth);
        return merged;
    }

    /** 复制入站头并剔除只属于「调用方 → 本网关」这一跳的那几个，见 {@link #INBOUND_ONLY_HEADERS}。 */
    private static HttpHeaders strip(HttpHeaders inbound) {
        HttpHeaders merged = new HttpHeaders();
        merged.putAll(inbound);
        INBOUND_ONLY_HEADERS.forEach(merged::remove);
        return merged;
    }
}
