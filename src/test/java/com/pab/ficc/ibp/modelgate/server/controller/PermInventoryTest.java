package com.pab.ficc.ibp.modelgate.server.controller;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * 管理接口清单与 {@code spec/modelgate/13-管理接口权限清单.md} 的一致性守卫（main 方式，不引 JUnit）。
 *
 * <p><b>为什么必须机械钉住</b>：内网应用网关按 perm 表逐接口拦截，而 perm 的录入依据就是
 * 那份清单。新增一个管理接口却忘了同步清单，该接口在 perm 里就没有条目——
 * 取决于网关的默认策略，要么<b>无人可用</b>（表现为"新功能上线即报无权限"），
 * 要么<b>无保护</b>（表现为谁都能调，而且不会有任何报错提示你漏了）。
 * 后者是安静失败，靠人记不住。
 *
 * <p>本用例<b>用反射</b>枚举 Controller 上的方法级映射，而不是扫源码正则——
 * 反射拿到的是编译后真实生效的映射，改注解写法（比如把 {@code @GetMapping("/x")}
 * 换成 {@code @RequestMapping(method=GET)}）不会骗过它。
 *
 * <p>清单里的 {@code /actuator/detection/**} 三条不由本用例校验：
 * 它们用 {@code @RequestMapping(method={GET,POST})} 且不进 perm（走网络层），
 * 单独列在清单的 L0 一节。
 */
public class PermInventoryTest {

    private static final Path DOC = Path.of("spec", "modelgate", "13-管理接口权限清单.md");

    /** 管理面 Controller；新增 Controller 时加进来（漏加会被 controllerListIsComplete 抓到）。 */
    private static final Class<?>[] CONTROLLERS = {
            AccessRuleController.class, AppController.class, AuditLogController.class,
            CredentialController.class, GatewayConfigController.class, ModelAuthController.class,
            ModelController.class, PayloadController.class, ServiceAuthController.class,
            ServiceController.class, UsageStatsController.class,
    };

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        // 重新生成清单时用：java ... PermInventoryTest --dump
        // 反射是唯一真相源——扫源码正则会被注释里的示例、无参注解、字符串字面量骗过去，
        // 生成清单时实测栽过一次（漏了 3 条 @GetMapping 无参的接口）。
        if (args.length > 0 && "--dump".equals(args[0])) {
            scanControllers().forEach(System.out::println);
            return;
        }
        if (!Files.exists(DOC)) {
            check("找得到 13-管理接口权限清单.md（需在项目根目录运行）", false);
            report();
            return;
        }
        String doc = Files.readString(DOC, StandardCharsets.UTF_8);

        Set<String> fromCode = scanControllers();
        Set<String> fromDoc = parseDoc(doc);

        inventoryMatches(fromCode, fromDoc);
        controllerListIsComplete();
        highRiskEndpointsAreClassified(doc);

        report();
    }

    /** 反射枚举「HTTP 方法 + 完整路径」。 */
    private static Set<String> scanControllers() {
        Set<String> out = new TreeSet<>();
        for (Class<?> c : CONTROLLERS) {
            RequestMapping base = c.getAnnotation(RequestMapping.class);
            String prefix = (base != null && base.value().length > 0) ? base.value()[0] : "";
            for (Method m : c.getDeclaredMethods()) {
                addIfMapped(out, prefix, m);
            }
        }
        return out;
    }

    private static void addIfMapped(Set<String> out, String prefix, Method m) {
        record Pair(Class<? extends java.lang.annotation.Annotation> type, String verb) {}
        List<Pair> kinds = List.of(
                new Pair(GetMapping.class, "GET"), new Pair(PostMapping.class, "POST"),
                new Pair(PutMapping.class, "PUT"), new Pair(PatchMapping.class, "PATCH"),
                new Pair(DeleteMapping.class, "DELETE"));
        for (Pair k : kinds) {
            java.lang.annotation.Annotation a = m.getAnnotation(k.type());
            if (a == null) {
                continue;
            }
            String[] value;
            try {
                value = (String[]) a.annotationType().getMethod("value").invoke(a);
            } catch (Exception e) {
                value = new String[0];
            }
            String sub = value.length > 0 ? value[0] : "";
            out.add(k.verb() + " " + prefix + sub);
        }
    }

    /** 从文档表格里取出 {@code | `GET` | `/modelgate/x` | ... |} 这样的行。 */
    private static Set<String> parseDoc(String doc) {
        Set<String> out = new TreeSet<>();
        Matcher m = Pattern.compile("\\|\\s*`([A-Z,]+)`\\s*\\|\\s*`(/[^`]+)`\\s*\\|").matcher(doc);
        while (m.find()) {
            if (m.group(2).startsWith("/actuator/")) {
                continue;   // L0 不进 perm，单列
            }
            out.add(m.group(1) + " " + m.group(2));
        }
        return out;
    }

    /** 双向比对：文档漏了新接口 → 无保护；文档多了已删接口 → perm 里留死条目。 */
    private static void inventoryMatches(Set<String> code, Set<String> doc) {
        List<String> missing = new ArrayList<>(code);
        missing.removeAll(doc);
        List<String> extra = new ArrayList<>(doc);
        extra.removeAll(code);

        check("代码里的接口都在清单中（缺 " + missing.size() + " 条"
                        + (missing.isEmpty() ? "" : "：" + missing) + "）",
                missing.isEmpty());
        check("清单里没有已删除的接口（多 " + extra.size() + " 条"
                        + (extra.isEmpty() ? "" : "：" + extra) + "）",
                extra.isEmpty());
        check("接口总数一致（代码 " + code.size() + " / 清单 " + doc.size() + "）",
                code.size() == doc.size());
    }

    /**
     * Controller 清单本身也会漂：新增一个 Controller 却没加进 {@link #CONTROLLERS}，
     * 上面的比对就会"两边都不知道它"而通过。这里按包内实际的 Controller 数交叉核对。
     */
    private static void controllerListIsComplete() throws IOException {
        Path dir = Path.of("src", "main", "java", "com", "pab", "ficc", "ibp",
                "modelgate", "server", "controller");
        if (!Files.isDirectory(dir)) {
            check("找得到 controller 目录", false);
            return;
        }
        long onDisk;
        try (var s = Files.list(dir)) {
            onDisk = s.filter(p -> p.getFileName().toString().endsWith("Controller.java"))
                    .filter(p -> !p.getFileName().toString().equals("ContainerDetectionController.java"))
                    .count();
        }
        check("CONTROLLERS 覆盖了包里全部管理面 Controller（磁盘 " + onDisk
                        + " / 清单 " + CONTROLLERS.length + "）",
                onDisk == CONTROLLERS.length);
    }

    /**
     * 高危接口必须被分到 L4，不能和普通增删改混在 L3。
     *
     * <p>凭证明文与报文留档是「拿到即可冒充调用方」的两条路径——
     * 业务面四层防护对它们全部无效，所以能改配置的人不一定该看得到它们。
     */
    private static void highRiskEndpointsAreClassified(String doc) {
        int l4 = doc.indexOf("### L4 · 凭证明文");
        // 不能用 indexOf("---") 找边界：Markdown 表格的分隔行 |---|---| 里就有 ---，
        // 会把区块截成一两行。取到「分级建议」那节为止。
        int end = doc.indexOf("## 分级建议", l4 < 0 ? 0 : l4);
        check("清单里存在 L4 分级", l4 >= 0);
        if (l4 < 0) {
            return;
        }
        String l4Block = doc.substring(l4, end < 0 ? doc.length() : end);
        for (String p : new String[]{"/modelgate/credentials/secret", "/modelgate/services/secret",
                "/modelgate/payload/query", "/modelgate/payload/detail", "/modelgate/payload/replay"}) {
            check(p + " 归在 L4", l4Block.contains("`" + p + "`"));
        }
    }

    private static void report() {
        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
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
