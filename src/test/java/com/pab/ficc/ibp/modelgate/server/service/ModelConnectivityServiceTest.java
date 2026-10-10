package com.pab.ficc.ibp.modelgate.server.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.adapter.AnthropicAdapter;
import com.pab.ficc.ibp.modelgate.server.adapter.OpenAIAdapter;
import com.pab.ficc.ibp.modelgate.server.adapter.RequestParamMerger;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.controller.ModelController;
import com.pab.ficc.ibp.modelgate.server.domain.bo.CreateModelRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.TestConnectionRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.bo.CredentialRequestBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.ConnectivityTestVO;
import com.sun.net.httpserver.HttpServer;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 管理面模型测试链路回归用例（main 方式，不额外引入测试框架）。
 *
 * <p>核心约束：modelId 是网关对外别名，真正发给供应商的请求体必须使用
 * upstreamModelId；快速探测、草稿测试和对话测试还必须复用业务转发的参数合并语义。
 */
public class ModelConnectivityServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final byte[] SMALL_RESPONSE =
            "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}".getBytes(StandardCharsets.UTF_8);
    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        AtomicReference<String> capturedBody = new AtomicReference<>();
        AtomicReference<byte[]> responseBody = new AtomicReference<>(SMALL_RESPONSE);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            capturedBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = responseBody.get();
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try {
            ModelConnectivityService service = new ModelConnectivityService(
                    List.of(new OpenAIAdapter(), new AnthropicAdapter(MAPPER)),
                    WebClient.builder(),
                    MAPPER,
                    new RequestParamMerger(MAPPER));

            ModelInstanceEntity model = model(server.getAddress().getPort());
            probeUsesRealUpstreamId(service, model, capturedBody);
            anthropicProbeUsesRealUpstreamId(service, model, capturedBody);
            largeManagementResponseExceedsWebClientDefault(service, model, responseBody);
            chatUsesRealIdAndConfiguredParams(service, model, capturedBody);
            draftCarriesAllRoutingFields();
            draftReusesOnlySavedCredential();
            modelParamConfigWriteValidation();
            draftTestRejectsBlankModelId();
            blankUpstreamIdFallsBackToExternalId();
        } finally {
            server.stop(0);
        }

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 1 MiB 页面上限不能被 WebClient 默认约 256 KiB 的解码上限提前截断。
     * 这里用 300 KiB 回包钉住专用管理面 codec 配置；旧实现会得到 DataBufferLimitException。
     */
    private static void largeManagementResponseExceedsWebClientDefault(
            ModelConnectivityService service, ModelInstanceEntity model,
            AtomicReference<byte[]> responseBody) throws Exception {
        String answer = "x".repeat(300 * 1024);
        var response = MAPPER.createObjectNode();
        response.putArray("choices").addObject()
                .putObject("message").put("content", answer);
        responseBody.set(response.toString().getBytes(StandardCharsets.UTF_8));
        try {
            ConnectivityTestVO result = service.probe(model, null).block(Duration.ofSeconds(10));
            check("管理面可读取超过 WebClient 默认 256KiB 的响应",
                    result != null && Boolean.TRUE.equals(result.getSuccess()));
            check("大响应正文按配置返回而非 DataBufferLimitException",
                    result != null && result.getUpstreamBody() != null
                            && result.getUpstreamBody().length() > 300 * 1024);
        } finally {
            responseBody.set(SMALL_RESPONSE);
        }
    }

    /** 协议转换后的 Anthropic 报文也必须保留真实上游模型 ID。 */
    private static void anthropicProbeUsesRealUpstreamId(ModelConnectivityService service,
                                                          ModelInstanceEntity model,
                                                          AtomicReference<String> capturedBody) throws Exception {
        model.setApiSpec("anthropic");
        model.setUpstreamPath(null);
        capturedBody.set(null);
        ConnectivityTestVO result = service.probe(model, null).block(Duration.ofSeconds(10));
        JsonNode sent = MAPPER.readTree(capturedBody.get());
        check("Anthropic 快速探测成功", result != null && Boolean.TRUE.equals(result.getSuccess()));
        check("Anthropic 转换后仍发送上游真实模型 ID",
                "provider/model-v1".equals(sent.path("model").asText()));
        model.setApiSpec("openai");
        model.setUpstreamPath("/v1/chat/completions");
    }

    /** 列表“连通性测试”的最小探测体必须直接命中真实模型 ID。 */
    private static void probeUsesRealUpstreamId(ModelConnectivityService service, ModelInstanceEntity model,
                                                AtomicReference<String> capturedBody) throws Exception {
        capturedBody.set(null);
        ConnectivityTestVO result = service.probe(model, null).block(Duration.ofSeconds(10));
        JsonNode sent = MAPPER.readTree(capturedBody.get());
        check("快速探测成功", result != null && Boolean.TRUE.equals(result.getSuccess()));
        check("快速探测发送上游真实模型 ID", "provider/model-v1".equals(sent.path("model").asText()));

        // 模型 ID 已开放自定义，探测体不能再靠字符串拼接，否则引号会生成非法 JSON。
        model.setUpstreamModelId("provider/\"quoted-id");
        service.probe(model, null).block(Duration.ofSeconds(10));
        check("快速探测正确转义自定义上游模型 ID",
                "provider/\"quoted-id".equals(MAPPER.readTree(capturedBody.get()).path("model").asText()));
        model.setUpstreamModelId("provider/model-v1");
    }

    /** 对话测试也要经过 default → force → upstreamModelId，与 /v1/** 的转发语义一致。 */
    private static void chatUsesRealIdAndConfiguredParams(ModelConnectivityService service,
                                                          ModelInstanceEntity model,
                                                          AtomicReference<String> capturedBody) throws Exception {
        model.setDefaultParams("{\"top_p\":0.8,\"temperature\":0.9}");
        model.setForceParams("{\"temperature\":0.2,\"model\":\"wrong-force-id\"}");
        byte[] body = "{\"model\":\"gateway-alias\",\"messages\":[]}"
                .getBytes(StandardCharsets.UTF_8);

        capturedBody.set(null);
        ConnectivityTestVO invalid = service.chat(model, null, body).block(Duration.ofSeconds(10));
        check("V2 拒绝 force_params.model 脏配置且不调用上游",
                invalid != null && !Boolean.TRUE.equals(invalid.getSuccess()) && capturedBody.get() == null);
        model.setForceParams("{\"temperature\":0.2}");
        ConnectivityTestVO result = service.chat(model, null, body).block(Duration.ofSeconds(10));
        JsonNode sent = MAPPER.readTree(capturedBody.get());
        check("管理面对话测试成功", result != null && Boolean.TRUE.equals(result.getSuccess()));
        check("对话测试仍由 upstreamModelId 决定路由身份",
                "provider/model-v1".equals(sent.path("model").asText()));
        check("对话测试应用 default_params", sent.path("top_p").asDouble() == 0.8d);
        check("对话测试应用 force_params", sent.path("temperature").asDouble() == 0.2d);
    }

    /** 新增/编辑弹窗的草稿 BO 不能再吞掉上游 ID 与参数配置。 */
    private static void draftCarriesAllRoutingFields() {
        TestConnectionRequestBO draft = new TestConnectionRequestBO();
        draft.setModelId("gateway-alias");
        draft.setUpstreamModelId("provider/model-v1");
        draft.setDefaultParams("{\"top_p\":0.8}");
        draft.setForceParams("{\"temperature\":0.2}");
        ModelInstanceEntity entity = draft.toEntity();

        check("草稿保留 upstreamModelId", "provider/model-v1".equals(entity.getUpstreamModelId()));
        check("草稿保留 defaultParams", draft.getDefaultParams().equals(entity.getDefaultParams()));
        check("草稿保留 forceParams", draft.getForceParams().equals(entity.getForceParams()));
    }

    private static void blankUpstreamIdFallsBackToExternalId() {
        ModelInstanceEntity model = new ModelInstanceEntity();
        model.setModelId("gateway-alias");
        model.setUpstreamModelId("  ");
        check("上游真实 ID 留空时回退对外 ID", "gateway-alias".equals(model.resolveUpstreamModelId()));
    }

    /** 管理写入口要在保存前拒绝脏参数配置，运行期容错不能代替写入校验。 */
    private static void modelParamConfigWriteValidation() {
        ModelController controller = new ModelController(null, null, null, MAPPER);
        CreateModelRequestBO request = new CreateModelRequestBO();
        request.setModelId("gateway-alias");
        request.setModelName("Gateway Alias");
        request.setProvider("openai");
        request.setUpstreamUrl("http://127.0.0.1");

        request.setDefaultParams("{bad-json");
        check("管理写入口拒绝非法参数 JSON", throwsBusiness(() -> controller.create(request)));

        request.setDefaultParams("{}");
        request.setForceParams("{\"model\":\"legacy-id\"}");
        check("管理写入口拒绝 forceParams.model", throwsBusiness(() -> controller.create(request)));

        request.setForceParams("{}");
        request.setParamMapping("{\"messages\":123}");
        check("管理写入口拒绝非字符串 mapping 目标", throwsBusiness(() -> controller.create(request)));

        request.setParamMapping("{\"messages\":\"inputs\"}");
        check("管理写入口接受合法参数对象", controller.create(request) != null);
    }

    /** 新增草稿必须有对外模型 ID，不能向生产上游发送 model="" 的无效探测。 */
    private static void draftTestRejectsBlankModelId() {
        ModelController controller = new ModelController(null, null, null, MAPPER);
        TestConnectionRequestBO draft = new TestConnectionRequestBO();
        draft.setUpstreamUrl("http://127.0.0.1");
        check("草稿测试拒绝空模型 ID", throwsBusiness(() -> controller.test(null, draft)));
    }

    private static boolean throwsBusiness(Runnable action) {
        try {
            action.run();
            return false;
        } catch (BusinessException expected) {
            return true;
        }
    }

    /** 编辑草稿复用旧密钥，但不能复用/修改旧凭证对象或丢弃草稿鉴权设置。 */
    private static void draftReusesOnlySavedCredential() {
        ModelCredentialEntity saved = new ModelCredentialEntity();
        saved.setAuthType("api_key");
        saved.setAccessKey("saved-ak");
        saved.setSecretKey("saved-secret");
        saved.setExtraConfig("{\"saved\":true}");

        CredentialRequestBO incoming = new CredentialRequestBO();
        incoming.setAuthType("api_key");
        TestConnectionRequestBO draft = new TestConnectionRequestBO();
        draft.setModelId("renamed-gateway-alias");
        draft.setCredential(incoming);
        ModelCredentialEntity merged = draft.toCredential(saved);

        check("编辑草稿留空时复用已保存密钥", "saved-secret".equals(merged.getSecretKey()));
        check("编辑草稿留空时复用已保存 AK", "saved-ak".equals(merged.getAccessKey()));
        check("复用凭证不修改缓存中的原对象", merged != saved);
        check("复用凭证时 modelId 跟随当前草稿",
                "renamed-gateway-alias".equals(merged.getModelId()));

        incoming.setAuthType("none");
        ModelCredentialEntity none = draft.toCredential(saved);
        check("草稿改为不鉴权时不携带历史 AK/SK",
                none.getAccessKey() == null && none.getSecretKey() == null);
    }

    private static ModelInstanceEntity model(int port) {
        ModelInstanceEntity model = new ModelInstanceEntity();
        model.setModelId("gateway-alias");
        model.setUpstreamModelId("provider/model-v1");
        model.setModelType("chat");
        model.setApiSpec("openai");
        model.setUpstreamUrl("http://127.0.0.1:" + port);
        model.setUpstreamPath("/v1/chat/completions");
        // 首次创建 Reactor Netty 客户端在冷 CI 上可能需要数秒，测试超时不应误判业务逻辑。
        model.setTimeoutMs(10_000);
        return model;
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
