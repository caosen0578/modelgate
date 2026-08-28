package com.pab.ficc.ibp.modelgate.server.filter;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;

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
        joinUpstreamHostOnly();
        joinUpstreamWithPath();
        joinMatchesProbe();

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
        // /svc/agent 与 /svc/agent/ 是同一个资源（服务根），剥完都归空串。
        // 补不补斜杠由 ServiceEntity.joinUpstream 按 upstreamUrl 带不带路径决定。
        check("剥离: /svc/agent/ → 空串（服务根）",
                "".equals(ServiceRouteFilter.resolveRemainder("/svc/agent/", "agent", true)));
        check("剥离: /svc/agent → 空串（服务根，与带尾斜杠等价）",
                "".equals(ServiceRouteFilter.resolveRemainder("/svc/agent", "agent", true)));
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
     * 拼接不能产生 {@code //}：upstreamUrl 末尾斜杠在写入时已规范化去掉，
     * 剩余路径若非空必定以 {@code /} 开头，两者拼接恰好一个斜杠。
     */
    private static void remainderNoDoubleSlash() {
        String full = svc("http://crm.intranet:8080").joinUpstream(
                ServiceRouteFilter.resolveRemainder("/svc/crm/api/query", "crm", true));
        check("完整拼接正确", "http://crm.intranet:8080/api/query".equals(full));
        check("拼接不产生 //", !full.contains("8080//"));
    }

    /**
     * upstreamUrl 只到 host:port 时，服务根必须补出 {@code "/"}。
     *
     * <p>不补会拼出没有 path 的地址（{@code http://host:8080}），有的上游直接 400。
     */
    private static void joinUpstreamHostOnly() {
        ServiceEntity s = svc("http://crm.intranet:8080");
        check("host-only + 空 → 补 /", "http://crm.intranet:8080/".equals(s.joinUpstream("")));
        check("host-only + 业务路径", "http://crm.intranet:8080/api/query".equals(s.joinUpstream("/api/query")));
        check("host-only 尾斜杠被规范化",
                "http://crm.intranet:8080/".equals(svc("http://crm.intranet:8080///").joinUpstream("")));
    }

    /**
     * upstreamUrl 已带业务路径时，服务根<b>不能</b>再补斜杠。
     *
     * <p>这是实际踩到的 404：{@code http://127.0.0.1:9000/chat/completions} 被拼成
     * {@code /chat/completions/}，多数框架当成另一条路由，运营看到的是
     * "地址明明填对了却 404"。
     */
    private static void joinUpstreamWithPath() {
        ServiceEntity s = svc("http://127.0.0.1:9000/chat/completions");
        check("带路径 + 空 → 原样，不补尾斜杠",
                "http://127.0.0.1:9000/chat/completions".equals(s.joinUpstream("")));
        check("带路径 + 空，不产生 completions/",
                !s.joinUpstream("").endsWith("completions/"));
        check("带路径 + 子路径仍正常拼",
                "http://127.0.0.1:9000/chat/completions/v2".equals(s.joinUpstream("/v2")));
    }

    /**
     * 转发与管理面探测必须落到<b>同一个</b>地址。
     *
     * <p>两边各拼各的会出现"探测通了但转发 404"——这类不一致最难查，
     * 因为两个界面各自看都是对的。这里把 {@code ServiceConnectivityService} 对
     * "路径留空"的归一化（空 / {@code "/"} → 空串）与转发侧摆在一起断言。
     */
    private static void joinMatchesProbe() {
        ServiceEntity s = svc("http://127.0.0.1:9000/chat/completions");
        String forwarded = s.joinUpstream(ServiceRouteFilter.resolveRemainder("/svc/testsse/", "testsse", true));
        String probed = s.joinUpstream(normalizeProbePath(null));
        check("探测地址 == 转发地址（路径均留空）", forwarded.equals(probed));
        check("探测地址 == 转发地址（探测填了裸 /）", forwarded.equals(s.joinUpstream(normalizeProbePath("/"))));
    }

    /** 与 {@code ServiceConnectivityService.probe} 里的归一化保持一致。 */
    private static String normalizeProbePath(String raw) {
        String p = (raw == null || raw.isBlank()) ? "" : raw.trim();
        if (!p.isEmpty() && !p.startsWith("/")) {
            p = "/" + p;
        }
        return "/".equals(p) ? "" : p;
    }

    private static ServiceEntity svc(String upstreamUrl) {
        ServiceEntity s = new ServiceEntity();
        s.setUpstreamUrl(upstreamUrl);
        return s;
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
