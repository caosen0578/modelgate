package com.pab.ficc.ibp.modelgate.server.filter;

/**
 * 服务链路的路径解析回归用例（main 方式，不额外引入测试框架）。
 *
 * <p>覆盖两件事，都是"错了很难从现象反推"的那种：
 * <ul>
 *   <li>{@code ServiceAuthFilter.extractServiceId} —— 从 {@code /svc/{code}/**} 取服务编码。
 *       取错的后果是路由到别的服务，或把整段路径当成编码去查库然后 404。</li>
 *   <li>{@code ServiceRouteFilter.resolveRemainder} —— 算出拼到 upstreamUrl 之后的那段。
 *       多一个斜杠或少一段路径，上游只会回 404，看不出是网关拼错了。</li>
 * </ul>
 */
public class ServiceRoutePathTest {

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        extractNormal();
        extractEdgeCases();
        remainderStripped();
        remainderNotStripped();
        remainderNoDoubleSlash();

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void extractNormal() {
        check("/svc/crm/api/v2/query → crm",
                "crm".equals(ServiceAuthFilter.extractServiceId("/svc/crm/api/v2/query")));
        check("/svc/kb/search → kb",
                "kb".equals(ServiceAuthFilter.extractServiceId("/svc/kb/search")));
        check("/svc/harness → harness（无后续段）",
                "harness".equals(ServiceAuthFilter.extractServiceId("/svc/harness")));
        check("/svc/agent/ → agent（尾斜杠）",
                "agent".equals(ServiceAuthFilter.extractServiceId("/svc/agent/")));
    }

    /**
     * 取不到编码时必须返回 null，由调用方转 404。
     *
     * <p>返回空串或整段路径都会导致"拿一个不存在的编码去查库"，
     * 虽然最终也是 404，但日志里看到的是一个莫名其妙的服务编码，排查时会往数据上找。
     */
    private static void extractEdgeCases() {
        check("/svc/ → null", ServiceAuthFilter.extractServiceId("/svc/") == null);
        check("/svc → null", ServiceAuthFilter.extractServiceId("/svc") == null);
        check("/svc// → null（空段）", ServiceAuthFilter.extractServiceId("/svc//") == null);
        check("/svc//api → null（空段）", ServiceAuthFilter.extractServiceId("/svc//api") == null);
    }

    /** 默认剥掉 /svc/{编码}，接入方内部路径原样保留。 */
    private static void remainderStripped() {
        check("剥离: /svc/crm/api/v2/query → /api/v2/query",
                "/api/v2/query".equals(ServiceRouteFilter.resolveRemainder("/svc/crm/api/v2/query", "crm", true)));
        check("剥离: /svc/kb/search/sse → /search/sse",
                "/search/sse".equals(ServiceRouteFilter.resolveRemainder("/svc/kb/search/sse", "kb", true)));
        check("剥离: /svc/agent/ → /",
                "/".equals(ServiceRouteFilter.resolveRemainder("/svc/agent/", "agent", true)));
    }

    /** 关闭剥离时完整路径原样转发。 */
    private static void remainderNotStripped() {
        check("不剥离: /svc/crm/api/v2/query 原样",
                "/svc/crm/api/v2/query".equals(
                        ServiceRouteFilter.resolveRemainder("/svc/crm/api/v2/query", "crm", false)));
        check("不剥离: /svc/crm 原样",
                "/svc/crm".equals(ServiceRouteFilter.resolveRemainder("/svc/crm", "crm", false)));
    }

    /**
     * 剥完为空时必须补 {@code "/"}，不能返回空串。
     *
     * <p>返回空串会拼出没有路径的 URL（{@code http://host:8080} 而不是 {@code http://host:8080/}），
     * 有的上游对此直接 400。而 upstreamUrl 那一侧的末尾斜杠已经在写入时被规范化去掉，
     * 两边都不带斜杠就会拼出一个没有 path 的地址。
     */
    private static void remainderNoDoubleSlash() {
        String r = ServiceRouteFilter.resolveRemainder("/svc/crm", "crm", true);
        check("剥离后为空 → 补 /", "/".equals(r));
        check("拼接不产生 //",
                !("http://crm.intranet:8080" + r).contains("8080//"));

        // 与 ServiceEntity.normalizedUpstreamUrl 配合：上游地址末尾斜杠已去掉，
        // 剩余路径必定以 / 开头，两者拼接恰好一个斜杠
        String full = "http://crm.intranet:8080"
                + ServiceRouteFilter.resolveRemainder("/svc/crm/api/query", "crm", true);
        check("完整拼接正确", "http://crm.intranet:8080/api/query".equals(full));
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
