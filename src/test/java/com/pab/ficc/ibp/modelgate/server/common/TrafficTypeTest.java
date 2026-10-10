package com.pab.ficc.ibp.modelgate.server.common;

/**
 * 流量类型判定的回归用例（main 方式，不额外引入测试框架）。
 *
 * <p>这套判定是整条过滤器链的分流依据：判错的后果是"某类流量被错误的过滤器拦截或误放行"——
 * 比如管理接口被当成业务流量要求带凭证、或服务流量被 BodyParseFilter 白白缓冲整个请求体。
 * 规则本身很短，但错了极难从现象反推，所以逐条钉死。
 */
public class TrafficTypeTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        modelTraffic();
        serviceTraffic();
        internalTraffic();
        unknownFallsBackToBusiness();
        routedButNotPrefixed();
        explicitPolicyComparison();
        prefixBoundaries();
        isBusinessSemantics();

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void modelTraffic() {
        check("/v1/chat/completions → MODEL",
                TrafficType.of("/v1/chat/completions") == TrafficType.MODEL);
        check("/v1/embeddings → MODEL",
                TrafficType.of("/v1/embeddings") == TrafficType.MODEL);
    }

    private static void serviceTraffic() {
        check("/svc/crm/api/v2/query → SERVICE",
                TrafficType.of("/svc/crm/api/v2/query") == TrafficType.SERVICE);
        check("/svc/kb/search/sse → SERVICE",
                TrafficType.of("/svc/kb/search/sse") == TrafficType.SERVICE);
    }

    private static void internalTraffic() {
        check("/models → INTERNAL",
                TrafficType.of("/models") == TrafficType.INTERNAL);
        check("/actuator/health → INTERNAL",
                TrafficType.of("/actuator/health") == TrafficType.INTERNAL);
        check("/actuator/detection/running → INTERNAL",
                TrafficType.of("/actuator/detection/running") == TrafficType.INTERNAL);
    }

    /**
     * 认不出来的路径落 UNKNOWN，且**必须算业务流量**（fail-closed）。
     *
     * <p>不能落 INTERNAL —— 那是"免鉴权"的意思。见 routedButNotPrefixed()：
     * 路由谓词 Path=/v1/** 的接受集比 startsWith("/v1/") 宽，差集里的路径能真的
     * 进到过滤器链，判成 INTERNAL 就是鉴权绕过。
     */
    private static void unknownFallsBackToBusiness() {
        check("/ 落 UNKNOWN", TrafficType.of("/") == TrafficType.UNKNOWN);
        check("/favicon.ico 落 UNKNOWN", TrafficType.of("/favicon.ico") == TrafficType.UNKNOWN);
        check("/v2/chat 落 UNKNOWN（不是 /v1/）", TrafficType.of("/v2/chat") == TrafficType.UNKNOWN);
        check("UNKNOWN 必须算业务流量（fail-closed）", TrafficType.UNKNOWN.isBusiness());
    }

    /**
     * <b>本文件最重要的一组</b>：路由谓词能匹配、但前缀判定匹配不上的路径。
     *
     * <p>实测（PathPatternParser.parse("/v1/**") 对 PathContainer.parsePath(p)）：
     * <pre>
     *   /v1                       routed=true   startsWith("/v1/")=false   ← ** 可匹配零个段
     *   /v1;a=b                   routed=true   startsWith("/v1/")=false   ← 矩阵参数被谓词剥离
     *   /v1;a=b/chat/completions  routed=true   startsWith("/v1/")=false
     * </pre>
     * 这些请求<b>进得到过滤器链</b>，必须判成业务流量、照常走凭证鉴权，
     * 否则就是匿名请求被直接代理到上游。
     *
     * <p>重构过程中确实一度判成了 INTERNAL（鉴权绕过），这组用例钉死它不再回去。
     */
    private static void routedButNotPrefixed() {
        for (String p : new String[]{"/v1", "/v1;a=b", "/v1;a=b/chat/completions"}) {
            check(p + " 必须算业务流量（路由匹配得到它）", TrafficType.of(p).isBusiness());
        }
        // 同一个坑在 /svc 上：下一批加 Path=/svc/** 路由后，/svc 与 /svc;x=y/... 同理
        for (String p : new String[]{"/svc", "/svc;x=y", "/svc;x=y/api/query"}) {
            check(p + " 必须算业务流量", TrafficType.of(p).isBusiness());
        }
    }

    /**
     * 与明确资源白名单策略逐路径对照；部署文根由 ApplicationPathsTest 单独覆盖。
     * <p>当前两类守卫：
     * <ul>
     *   <li>通用能力过滤器：仅明确管理资源与 actuator 不执行</li>
     *   <li>模型专属过滤器：{@code startsWith("/v1/")} 才执行</li>
     * </ul>
     * 分别对应 {@code isBusiness()} 与 {@code == MODEL}。
     */
    private static void explicitPolicyComparison() {
        String[] paths = {
                "/v1/chat/completions", "/v1/embeddings", "/v1/", "/v1", "/v1;a=b",
                "/v1;a=b/chat/completions", "/v1abc/x", "/v2/chat",
                "/svc/crm/api", "/svc", "/svcabc/x",
                "/models", "/models/page", "/services/list", "/apps/detail", "/credentials/secret", "/modelgate/", "/actuator/health",
                "/actuator/detection/running", "/", "/favicon.ico", "/foo/bar",
        };
        boolean allOk = true;
        for (String p : paths) {
            boolean oldBusiness = !(com.pab.ficc.ibp.modelgate.server.constant.UrlConstant.MANAGE_PATHS.stream()
                    .anyMatch(root -> p.equals(root) || p.startsWith(root + "/")) || p.startsWith("/actuator/"));
            boolean oldModel = p.startsWith("/v1/");
            TrafficType t = TrafficType.of(p);
            if (t.isBusiness() != oldBusiness || (t == TrafficType.MODEL) != oldModel) {
                allOk = false;
                System.err.println("      不等价: " + p + " old(business=" + oldBusiness
                        + ",model=" + oldModel + ") new(" + t + ")");
            }
        }
        check("符合显式资源边界策略（" + paths.length + " 条路径 × 2 类守卫）", allOk);
    }

    /**
     * 前缀必须带尾斜杠比较，否则 {@code /v1abc} 会被误判成模型流量。
     *
     * <p>常量 BUSINESS_PREFIX="/v1/"、SERVICE_PREFIX="/svc/" 都带尾斜杠，
     * 这几条是防止有人"顺手"把尾斜杠去掉。
     */
    private static void prefixBoundaries() {
        check("/v1abc/x 不是 MODEL", TrafficType.of("/v1abc/x") != TrafficType.MODEL);
        check("/svcabc/x 不是 SERVICE", TrafficType.of("/svcabc/x") != TrafficType.SERVICE);
        check("/modelgateX 不命中管理面前缀（落 UNKNOWN，仍需鉴权）",
                TrafficType.of("/modelgateX") == TrafficType.UNKNOWN);
        check("/v1/ 本身 → MODEL", TrafficType.of("/v1/") == TrafficType.MODEL);
        check("/svc/ 本身 → SERVICE", TrafficType.of("/svc/") == TrafficType.SERVICE);
    }

    /** isBusiness() 决定通用能力过滤器（凭证/访问控制/配额/报文/熔断）要不要执行。 */
    private static void isBusinessSemantics() {
        check("MODEL 是业务流量", TrafficType.MODEL.isBusiness());
        check("SERVICE 是业务流量", TrafficType.SERVICE.isBusiness());
        check("INTERNAL 不是业务流量", !TrafficType.INTERNAL.isBusiness());
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
