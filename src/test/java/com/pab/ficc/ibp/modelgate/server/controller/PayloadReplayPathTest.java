package com.pab.ficc.ibp.modelgate.server.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 报文回放的路径还原规则回归用例（main 方式，不引 JUnit）。
 *
 * <p>回放服务报文时要把留档的入站路径 {@code /svc/{编码}/api/x} 还原成
 * 拼到上游地址之后的那一段 {@code /api/x}。这条规则现在有<b>三份实现</b>：
 * <ol>
 *   <li>{@code ServiceRouteFilter.resolveRemainder}（真实转发）</li>
 *   <li>{@code PayloadController.stripSvcPrefix}（回放，本用例的被测对象）</li>
 *   <li>admin.html 的 {@code stripSvcPrefixForReplay}（弹窗里回显要发往哪）</li>
 * </ol>
 *
 * <p>"同一规则散成多份副本"正是这个项目反复出问题的形状——
 * 尾斜杠那个 404 就是三处预览各拼各的造成的，修了两轮才补齐。
 * 所以这里既断言后端行为，也<b>直接读 admin.html 源码</b>交叉断言前端那份还在、且规则一致。
 */
public class PayloadReplayPathTest {

    private static final Path ADMIN_HTML =
            Path.of("src", "main", "resources", "static", "admin.html");

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        stripsPrefixNormally();
        serviceRootBecomesNull();
        leavesForeignPathsAlone();
        handlesMissingInput();
        frontendMirrorExists();

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    private static void stripsPrefixNormally() {
        check("/svc/crm/api/v2/query → /api/v2/query",
                "/api/v2/query".equals(PayloadController.stripSvcPrefix("/svc/crm/api/v2/query", "crm")));
        check("带 query 一并保留",
                "/api/q?x=1".equals(PayloadController.stripSvcPrefix("/svc/crm/api/q?x=1", "crm")));
        check("/svc/testsse/chat/completions → /chat/completions",
                "/chat/completions".equals(
                        PayloadController.stripSvcPrefix("/svc/testsse/chat/completions", "testsse")));
    }

    /**
     * 服务根必须还原成 {@code null}（= 交给 joinUpstream 决定补不补斜杠），
     * <b>不能是 {@code "/"}</b>。
     *
     * <p>返回 {@code "/"} 会让上游地址自带路径的服务被拼出
     * {@code /chat/completions/} 而 404——就是 32d77c7 修的那个 bug 在回放链路上重演。
     */
    private static void serviceRootBecomesNull() {
        check("/svc/crm → null（服务根）",
                PayloadController.stripSvcPrefix("/svc/crm", "crm") == null);
        check("/svc/crm/ → null（服务根，与不带尾斜杠等价）",
                PayloadController.stripSvcPrefix("/svc/crm/", "crm") == null);
    }

    /**
     * 前缀对不上时原样返回：{@code stripPrefix=false} 的服务转发的就是完整路径，
     * 这里再剥一次会把路径改错。
     */
    private static void leavesForeignPathsAlone() {
        check("前缀不匹配 → 原样返回",
                "/api/v2/query".equals(PayloadController.stripSvcPrefix("/api/v2/query", "crm")));
        check("编码不同 → 原样返回",
                "/svc/kb/search".equals(PayloadController.stripSvcPrefix("/svc/kb/search", "crm")));
    }

    private static void handlesMissingInput() {
        check("路径为 null → null", PayloadController.stripSvcPrefix(null, "crm") == null);
        check("路径为空串 → null", PayloadController.stripSvcPrefix("", "crm") == null);
        check("路径全空白 → null", PayloadController.stripSvcPrefix("   ", "crm") == null);
    }

    /**
     * 前端那份镜像必须存在且规则一致。
     *
     * <p>只断言"函数还在、关键分支还在"——不做 JS 求值。它的价值是：
     * 有人删掉或改写前端那份时这条会失败，提醒两边要一起改。
     */
    private static void frontendMirrorExists() throws IOException {
        if (!Files.exists(ADMIN_HTML)) {
            check("找得到 admin.html（需在项目根目录运行）", false);
            return;
        }
        String html = Files.readString(ADMIN_HTML, StandardCharsets.UTF_8);
        int i = html.indexOf("function stripSvcPrefixForReplay(");
        check("admin.html 里存在 stripSvcPrefixForReplay", i >= 0);
        if (i < 0) {
            return;
        }
        int end = html.indexOf("\n}", i);
        String body = html.substring(i, end < 0 ? Math.min(i + 600, html.length()) : end);

        check("前端也用 '/svc/' + serviceId 作前缀", body.contains("'/svc/' + serviceId"));
        check("前端前缀不匹配时原样返回", body.contains("return requestPath"));
        check("前端把服务根（'' 与 '/'）都归 null",
                body.contains("rest === ''") && body.contains("rest === '/'"));
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
