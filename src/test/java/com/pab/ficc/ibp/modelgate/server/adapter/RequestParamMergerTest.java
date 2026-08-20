package com.pab.ficc.ibp.modelgate.server.adapter;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;

import java.nio.charset.StandardCharsets;

/**
 * {@link RequestParamMerger} 回归用例。与项目其它测试一致，用 {@code main()}，不引入测试框架。
 *
 * <p>覆盖的是三项语义里最容易搞反的地方：
 * default 只补缺、force 无条件覆盖、mapping 只在 custom 下生效且不覆盖已有键，
 * 以及"三项都没配时必须原样返回同一个数组引用"（性能保证）。
 *
 * <p>运行方式（项目根目录）：
 * <pre>
 * java -cp "target/classes;target/test-classes;$(cat cp.txt)" \
 *      com.pab.ficc.ibp.modelgate.server.adapter.RequestParamMergerTest
 * </pre>
 */
public class RequestParamMergerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final RequestParamMerger MERGER = new RequestParamMerger(MAPPER);

    private static int pass = 0;
    private static int fail = 0;

    public static void main(String[] args) throws Exception {
        noConfigIsZeroCost();
        defaultsOnlyFillMissing();
        forceAlwaysOverwrites();
        orderDefaultThenForce();
        mappingOnlyForCustom();
        mappingDoesNotClobber();
        badConfigFallsBack();
        modelRename();

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 三项都没配 → 必须返回**同一个数组引用**，证明没解析也没分配 */
    private static void noConfigIsZeroCost() {
        byte[] body = bytes("{\"model\":\"m\",\"messages\":[]}");
        ModelInstanceEntity m = model("openai", null, null, null);
        byte[] out = MERGER.merge(body, m);
        check("未配置时原样返回同一引用（零开销）", out == body);
    }

    /** default_params 只填补缺失字段，调用方已传的一律不动 */
    private static void defaultsOnlyFillMissing() throws Exception {
        ModelInstanceEntity m = model("openai", "{\"temperature\":0.7,\"top_p\":1}", null, null);
        JsonNode out = merge("{\"model\":\"m\",\"temperature\":0.1}", m);
        check("default 补缺失字段", out.get("top_p").asInt() == 1);
        check("default 不覆盖调用方已传的值", out.get("temperature").asDouble() == 0.1);
    }

    /** force_params 无条件覆盖 */
    private static void forceAlwaysOverwrites() throws Exception {
        ModelInstanceEntity m = model("openai", null, "{\"max_tokens\":512}", null);
        JsonNode out = merge("{\"model\":\"m\",\"max_tokens\":8000}", m);
        check("force 覆盖调用方的值", out.get("max_tokens").asInt() == 512);

        JsonNode out2 = merge("{\"model\":\"m\"}", m);
        check("force 在字段缺失时也写入", out2.get("max_tokens").asInt() == 512);
    }

    /** 同一字段两边都配时，force 必须赢 */
    private static void orderDefaultThenForce() throws Exception {
        ModelInstanceEntity m = model("openai", "{\"max_tokens\":100}", "{\"max_tokens\":512}", null);
        JsonNode out = merge("{\"model\":\"m\"}", m);
        check("同字段 force 优先于 default", out.get("max_tokens").asInt() == 512);
    }

    /** param_mapping 仅 api_spec=custom 生效 */
    private static void mappingOnlyForCustom() throws Exception {
        String mapping = "{\"messages\":\"inputs\"}";

        JsonNode openai = merge("{\"model\":\"m\",\"messages\":[1]}", model("openai", null, null, mapping));
        check("非 custom 时 mapping 不生效", openai.has("messages") && !openai.has("inputs"));

        JsonNode custom = merge("{\"model\":\"m\",\"messages\":[1]}", model("custom", null, null, mapping));
        check("custom 时字段被改名", custom.has("inputs") && !custom.has("messages"));
    }

    /** 目标键已存在时跳过，不能静默覆盖掉原有数据 */
    private static void mappingDoesNotClobber() throws Exception {
        ModelInstanceEntity m = model("custom", null, null, "{\"messages\":\"inputs\"}");
        JsonNode out = merge("{\"model\":\"m\",\"messages\":[1],\"inputs\":[2]}", m);
        check("mapping 目标键已存在时跳过不覆盖",
                out.get("inputs").get(0).asInt() == 2 && out.has("messages"));
    }

    /** 配置不是合法 JSON → 原样放行，不能让业务请求失败 */
    private static void badConfigFallsBack() {
        byte[] body = bytes("{\"model\":\"m\"}");
        ModelInstanceEntity m = model("openai", null, "{不是合法JSON", null);
        byte[] out = MERGER.merge(body, m);
        check("配置非法时原样放行不抛异常", out == body || new String(out, StandardCharsets.UTF_8).contains("\"m\""));

        byte[] bad = bytes("{不是合法JSON");
        byte[] out2 = MERGER.merge(bad, model("openai", null, "{\"a\":1}", null));
        check("请求体非法时原样放行不抛异常", out2 == bad);
    }

    /** 用户的实际场景：同一真实模型部署在两个平台，网关对外用不同 ID，转发时改回真实 ID */
    private static void modelRename() throws Exception {
        ModelInstanceEntity m = model("openai", null, "{\"model\":\"DeepSeek-V3\"}", null);
        JsonNode out = merge("{\"model\":\"deepseek-v3-platform-b\",\"messages\":[]}", m);
        check("force_params 可实现模型 ID 改名", "DeepSeek-V3".equals(out.get("model").asText()));
    }

    // ===================== 工具 =====================

    private static JsonNode merge(String body, ModelInstanceEntity m) throws Exception {
        return MAPPER.readTree(MERGER.merge(bytes(body), m));
    }

    private static ModelInstanceEntity model(String apiSpec, String defaults, String forced, String mapping) {
        ModelInstanceEntity m = new ModelInstanceEntity();
        m.setModelId("test-model");
        m.setApiSpec(apiSpec);
        m.setDefaultParams(defaults);
        m.setForceParams(forced);
        m.setParamMapping(mapping);
        return m;
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void check(String name, boolean ok) {
        if (ok) {
            pass++;
            System.out.println("PASS " + name);
        } else {
            fail++;
            System.out.println("FAIL " + name);
        }
    }
}
