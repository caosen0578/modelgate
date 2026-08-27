package com.pab.ficc.ibp.modelgate.server.filter;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/**
 * 上游请求头构造的回归用例（main 方式，不额外引入测试框架）。
 *
 * <p><b>守的是一条安全属性</b>：调用方带进来的 {@code Authorization} 是
 * {@code mg_app_credential} 里的<b>本网关</b>凭证，<b>任何情况下都不得转发给上游模型厂商</b>。
 *
 * <p>这条属性此前是破的，而且破得很隐蔽：合并逻辑靠"上游头覆盖同名的调用方头"来去除，
 * 只有 OpenAI 适配器用 {@code Authorization} 才碰巧成立；Azure（{@code api-key}）、
 * Anthropic（{@code x-api-key}）、Custom 的 ak_sk（{@code X-Access-Key}）这三条路径下
 * 调用方凭证会原样出网。下面四个用例按 api_spec 逐一钉死，新增适配器时照抄一条。
 */
public class UpstreamHeaderBuilderTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        openAiOverwritesAuthorization();
        azureKeepsNoCallerCredential();
        anthropicKeepsNoCallerCredential();
        customAkSkKeepsNoCallerCredential();
        businessHeadersSurvive();
        internalHeadersStripped();
        caseInsensitiveStrip();
        contentLengthRewritten();
        serviceOverloadKeepsInboundContentLength();
        serviceOverloadKeepsChunked();

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** OpenAI：上游同样用 Authorization，必须是上游的那把 key，不是调用方的。 */
    private static void openAiOverwritesAuthorization() {
        HttpHeaders upstream = new HttpHeaders();
        upstream.setContentType(MediaType.APPLICATION_JSON);
        upstream.setBearerAuth("upstream-sk-openai");

        HttpHeaders out = UpstreamHeaderBuilder.build(inbound(), upstream, 10);
        check("openai: Authorization 是上游凭证",
                "Bearer upstream-sk-openai".equals(out.getFirst(HttpHeaders.AUTHORIZATION)));
        check("openai: 不含调用方凭证",
                !String.valueOf(out.getFirst(HttpHeaders.AUTHORIZATION)).contains("caller-gateway-key"));
    }

    /** Azure：上游用 api-key，调用方的 Authorization 没有同名头去覆盖它，必须被显式删掉。 */
    private static void azureKeepsNoCallerCredential() {
        HttpHeaders upstream = new HttpHeaders();
        upstream.set("api-key", "upstream-azure-key");

        HttpHeaders out = UpstreamHeaderBuilder.build(inbound(), upstream, 10);
        check("azure: 调用方 Authorization 已剔除", out.getFirst(HttpHeaders.AUTHORIZATION) == null);
        check("azure: 上游 api-key 保留", "upstream-azure-key".equals(out.getFirst("api-key")));
    }

    /** Anthropic：上游用 x-api-key，同上。 */
    private static void anthropicKeepsNoCallerCredential() {
        HttpHeaders upstream = new HttpHeaders();
        upstream.set("x-api-key", "upstream-anthropic-key");

        HttpHeaders out = UpstreamHeaderBuilder.build(inbound(), upstream, 10);
        check("anthropic: 调用方 Authorization 已剔除", out.getFirst(HttpHeaders.AUTHORIZATION) == null);
        check("anthropic: 上游 x-api-key 保留", "upstream-anthropic-key".equals(out.getFirst("x-api-key")));
    }

    /** Custom + ak_sk：上游用 X-Access-Key / X-Secret-Key，同上。 */
    private static void customAkSkKeepsNoCallerCredential() {
        HttpHeaders upstream = new HttpHeaders();
        upstream.set("X-Access-Key", "ak");
        upstream.set("X-Secret-Key", "sk");

        HttpHeaders out = UpstreamHeaderBuilder.build(inbound(), upstream, 10);
        check("custom-aksk: 调用方 Authorization 已剔除", out.getFirst(HttpHeaders.AUTHORIZATION) == null);
        check("custom-aksk: 上游 AK/SK 保留",
                "ak".equals(out.getFirst("X-Access-Key")) && "sk".equals(out.getFirst("X-Secret-Key")));
    }

    /**
     * 只删凭证，不能误伤业务头。
     *
     * <p>调用方可能带自定义业务头（灰度标记、租户号、上游要求的 trace 头等），
     * 这些必须原样透传——剔除清单写宽了会在生产上表现为"某些请求上游行为异常"，极难定位。
     */
    private static void businessHeadersSurvive() {
        HttpHeaders in = inbound();
        in.set("X-Tenant-Id", "t-001");
        in.set("X-Request-Source", "harness");
        in.setAccept(java.util.List.of(MediaType.TEXT_EVENT_STREAM));

        HttpHeaders out = UpstreamHeaderBuilder.build(in, new HttpHeaders(), 10);
        check("业务头 X-Tenant-Id 透传", "t-001".equals(out.getFirst("X-Tenant-Id")));
        check("业务头 X-Request-Source 透传", "harness".equals(out.getFirst("X-Request-Source")));
        check("SSE 的 Accept 头透传",
                MediaType.TEXT_EVENT_STREAM_VALUE.equals(out.getFirst(HttpHeaders.ACCEPT)));
    }

    /** X-Mg-* 是进程内部传参（读取方 order 全部早于 -140），不应出网。 */
    private static void internalHeadersStripped() {
        HttpHeaders out = UpstreamHeaderBuilder.build(inbound(), new HttpHeaders(), 10);
        check("内部头 X-Mg-App 已剔除", out.getFirst("X-Mg-App") == null);
        check("内部头 X-Mg-Cred-Type 已剔除", out.getFirst("X-Mg-Cred-Type") == null);
    }

    /**
     * 调用方大小写不影响剔除。
     *
     * <p>HTTP 头名大小写不敏感，curl 写 {@code authorization}、SDK 写 {@code Authorization} 都合法。
     * 依赖的是 {@link HttpHeaders} 底层的 case-insensitive map；哪天有人把实现换成普通 Map，
     * 这条会先炸，而不是等到凭证已经泄出去才发现。
     */
    private static void caseInsensitiveStrip() {
        HttpHeaders in = new HttpHeaders();
        in.set("authorization", "Bearer caller-gateway-key");
        in.set("x-mg-app", "app-001");

        HttpHeaders out = UpstreamHeaderBuilder.build(in, new HttpHeaders(), 10);
        check("小写 authorization 同样被剔除", out.getFirst(HttpHeaders.AUTHORIZATION) == null);
        check("小写 x-mg-app 同样被剔除", out.getFirst("X-Mg-App") == null);
    }

    /** 请求体被适配器改写过，Content-Length 必须跟着改，否则上游按旧长度读会截断或挂起。 */
    private static void contentLengthRewritten() {
        HttpHeaders in = inbound();
        in.setContentLength(9999);

        HttpHeaders out = UpstreamHeaderBuilder.build(in, new HttpHeaders(), 42);
        check("Content-Length 取改写后的长度", out.getContentLength() == 42L);
    }

    /**
     * 服务链路的重载：请求体原样透传、没被改写，**绝不能**碰 Content-Length。
     *
     * <p>若这里跟着模型侧去 setContentLength，就必须先知道请求体多长，
     * 而那意味着要把整个 body 读进内存——把新链路好不容易省下的开销又付回去。
     */
    private static void serviceOverloadKeepsInboundContentLength() {
        HttpHeaders in = inbound();
        in.setContentLength(9999);

        HttpHeaders upstream = new HttpHeaders();
        upstream.set("X-Tenant", "ficc");   // custom_header 型上游凭证

        HttpHeaders out = UpstreamHeaderBuilder.build(in, upstream);
        check("service: 原样保留调用方 Content-Length", out.getContentLength() == 9999L);
        check("service: 调用方 Authorization 仍被剔除", out.getFirst(HttpHeaders.AUTHORIZATION) == null);
        check("service: 上游自定义头注入", "ficc".equals(out.getFirst("X-Tenant")));
    }

    /**
     * chunked 上传（没有 Content-Length）时，服务重载不得凭空造一个出来。
     *
     * <p>造一个错的 Content-Length 比没有更糟：上游会按它去读，读不满就一直挂着。
     */
    private static void serviceOverloadKeepsChunked() {
        HttpHeaders in = inbound();
        in.set(HttpHeaders.TRANSFER_ENCODING, "chunked");

        HttpHeaders out = UpstreamHeaderBuilder.build(in, new HttpHeaders());
        check("service: chunked 保留", "chunked".equals(out.getFirst(HttpHeaders.TRANSFER_ENCODING)));
        check("service: 不凭空造 Content-Length", out.getFirst(HttpHeaders.CONTENT_LENGTH) == null);
    }

    /** 典型入站请求头：调用方凭证 + CredentialAuthFilter(-190) 注入的内部头。 */
    private static HttpHeaders inbound() {
        HttpHeaders in = new HttpHeaders();
        in.setContentType(MediaType.APPLICATION_JSON);
        in.set(HttpHeaders.AUTHORIZATION, "Bearer caller-gateway-key");
        in.set("X-Mg-App", "app-001");
        in.set("X-Mg-Cred-Type", "api_key");
        return in;
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("  PASS  " + name);
        } else {
            fail++;
            System.err.println("  FAIL  " + name);
        }
    }
}
