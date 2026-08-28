package com.pab.ficc.ibp.modelgate.server.controller;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 服务侧管理接口必须与模型侧<b>同构</b>的回归用例（main 方式，不额外引入测试框架）。
 *
 * <p><b>为什么需要这个</b>：服务接入这一批里连续出了四个同源问题，全都是
 * "新代码没照抄既有约定"造成的：
 * <ol>
 *   <li>弹窗外层用了 {@code class="modal"}（应为 {@code modal-backdrop}）→ 两个弹窗常驻页面</li>
 *   <li>用了 {@code form-row}/{@code btn-xs} 等项目里根本不存在的 CSS 类 → 样式全不生效</li>
 *   <li>列表接口手拼 {@code {records:...}} 而非返回 {@code PageVo} → 列表恒空（见 PageContractTest）</li>
 *   <li>整套 HTTP 方法用了 POST，而模型侧是 POST/PUT/PATCH/DELETE → 前后端各写各的</li>
 * </ol>
 * 这些编译都不报错，只能靠"把两边摆在一起比"发现。本用例把第 4 类钉死：
 * <b>同一个语义的子路径，服务侧与模型侧必须用同一个 HTTP 方法。</b>
 *
 * <p>将来若有意让某个端点不同（比如服务侧多一个模型侧没有的操作），
 * 在 {@link #SERVICE_ONLY} 里登记并写明理由，而不是直接删断言。
 */
public class ServiceMirrorsModelTest {

    /** 服务侧独有、模型侧没有对应物的端点：登记后跳过比对 */
    private static final Map<String, String> SERVICE_ONLY = new LinkedHashMap<>();

    static {
        // 服务侧的凭证是"上游凭证"，模型侧对应的是 /models 上没有单独端点的 credential，
        // 查看明文这个动作模型侧放在 CredentialController 上，路径不同但语义一致，故不参与比对。
        SERVICE_ONLY.put("/secret", "查看上游凭证明文；模型侧同名动作在 CredentialController 上，路径不同");
        // 模型侧有连通性测试 /test 与对话测试 /chat/test，服务侧尚未实现（已登记为待办）
    }

    private static int pass;
    private static int fail;

    public static void main(String[] args) {
        compare("服务管理", ServiceController.class, ModelController.class);
        compare("服务授权", ServiceAuthController.class, ModelAuthController.class);

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /**
     * 逐个子路径比对两个 Controller 的 HTTP 方法。
     *
     * <p>只比<b>两边都有</b>的子路径：模型侧独有的（如 {@code /chat/test}）不要求服务侧实现，
     * 服务侧独有的在 {@link #SERVICE_ONLY} 登记。
     */
    private static void compare(String label, Class<?> serviceCtrl, Class<?> modelCtrl) {
        Map<String, Set<String>> svc = endpoints(serviceCtrl);
        Map<String, Set<String>> mdl = endpoints(modelCtrl);

        System.out.println("── " + label + "：" + serviceCtrl.getSimpleName()
                + " vs " + modelCtrl.getSimpleName());

        for (Map.Entry<String, Set<String>> e : svc.entrySet()) {
            String path = e.getKey();
            Set<String> svcVerb = e.getValue();
            if (SERVICE_ONLY.containsKey(path)) {
                System.out.println("  SKIP  " + path + " —— " + SERVICE_ONLY.get(path));
                continue;
            }
            Set<String> mdlVerb = mdl.get(path);
            if (mdlVerb == null) {
                // 模型侧没有这个子路径：可能是服务侧独有但没登记，提示而不判失败
                System.out.println("  SKIP  " + path + " —— 模型侧无同名子路径（服务侧独有，未登记）");
                continue;
            }
            check(String.format("%-10s 服务侧 %-14s == 模型侧 %-14s", path, svcVerb, mdlVerb),
                    svcVerb.equals(mdlVerb));
        }

        // 反向：模型侧有、服务侧缺的关键动作，提示出来（不判失败——服务侧可以不实现全部动作）
        for (String path : mdl.keySet()) {
            if (!svc.containsKey(path)) {
                System.out.println("  NOTE  模型侧有 " + path + "（" + mdl.get(path) + "），服务侧未实现");
            }
        }
    }

    /**
     * 取 Controller 上所有方法级映射：子路径 → 该路径支持的 HTTP 方法<b>集合</b>。
     *
     * <p>必须是集合而不是单值：集合根 {@code "/"} 上同时挂着 GET（列表）与 POST（新增），
     * 用单值 Map 会让后写入的那个覆盖前一个，断言就变成了拿 POST 比 GET 的假失败。
     */
    private static Map<String, Set<String>> endpoints(Class<?> ctrl) {
        Map<String, Set<String>> out = new TreeMap<>();
        for (Method m : ctrl.getDeclaredMethods()) {
            verbOf(m).ifPresent(v ->
                    out.computeIfAbsent(pathOf(m, v), k -> new TreeSet<>()).add(v.verb));
        }
        return out;
    }

    private record Mapping(String verb, String[] value) {}

    private static java.util.Optional<Mapping> verbOf(Method m) {
        for (Annotation a : m.getAnnotations()) {
            if (a instanceof GetMapping g)    return java.util.Optional.of(new Mapping("GET", g.value()));
            if (a instanceof PostMapping p)   return java.util.Optional.of(new Mapping("POST", p.value()));
            if (a instanceof PutMapping p)    return java.util.Optional.of(new Mapping("PUT", p.value()));
            if (a instanceof PatchMapping p)  return java.util.Optional.of(new Mapping("PATCH", p.value()));
            if (a instanceof DeleteMapping d) return java.util.Optional.of(new Mapping("DELETE", d.value()));
        }
        return java.util.Optional.empty();
    }

    /** 子路径；{@code @XxxMapping} 不带参数或参数为空串时统一记作 "/"（集合根）。 */
    private static String pathOf(Method m, Mapping mp) {
        String p = mp.value().length == 0 ? "" : mp.value()[0];
        return p.isEmpty() ? "/" : p;
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
