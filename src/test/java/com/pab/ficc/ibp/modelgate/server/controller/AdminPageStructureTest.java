package com.pab.ficc.ibp.modelgate.server.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code admin.html} 脚本区的结构守卫（main 方式，不额外引入测试框架）。
 *
 * <p><b>为什么需要这个用例</b>：管理页是单文件 5000+ 行（不拆多页的理由见
 * spec/modelgate/05 §1 禁前端 SPA 路由）。单文件本身没问题，出问题的是
 * <b>"公共"只靠习惯维持</b>——{@code escapeHtml} 曾长在模型管理区、
 * {@code statusTag} 长在报文记录区、{@code copyText}/{@code openModal} 长在文件末尾。
 * 新人照着最近的模块抄，很自然会在自己的模块里再写一份，然后两份慢慢分叉。
 *
 * <p>{@code svcJoinUpstream} 就是活例子：它被服务管理页、服务测试工具、凭证接入示例
 * <b>三个模块</b>共用，三处曾各拼各的，上游地址自带路径时多补一个尾斜杠导致 404
 * （32d77c7 修了两处、0722f24 才补上第三处）。
 *
 * <p>所以这里钉三件事：
 * <ol>
 *   <li>分区标记齐全且顺序与 {@link #SECTIONS} 一致（新增模块必须同步这里）</li>
 *   <li>{@link #COMMON_FUNCTIONS} 里的函数<b>确实定义在公共层区间内</b></li>
 *   <li>全文没有重复的顶层函数定义（同名两份 = 后定义的静默覆盖前一个）</li>
 * </ol>
 *
 * <p>本用例<b>不</b>与 {@code PageContractTest} 合并：那个守的是分页返回结构的
 * 前后端契约，两者失败时要看的地方完全不同，混在一起会让报错指向变模糊。
 */
public class AdminPageStructureTest {

    private static final Path ADMIN_HTML =
            Path.of("src", "main", "resources", "static", "admin.html");

    /** 分区标记的统一格式：{@code // ===== 公共层 · X} 或 {@code // ===== 模块 · X} */
    private static final Pattern BANNER = Pattern.compile("(?m)^// ===== (公共层|模块) · (.+)$");

    /** 顶格函数定义（模块内的嵌套函数不顶格，不会被匹配到） */
    private static final Pattern TOP_LEVEL_FN =
            Pattern.compile("(?m)^function ([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\(");

    /**
     * 期望的分区与顺序。<b>新增模块时改这里</b>，并同步 admin.html 顶部的目录注释。
     *
     * <p>顺序也钉住：公共层必须全部排在模块层之前——JS 函数声明虽然会提升，
     * 但把公共层散落在模块中间会让"这个函数是公共的还是某模块私有的"重新变成靠猜。
     */
    private static final List<String> SECTIONS = List.of(
            "公共层 · 全局状态",
            "公共层 · 工具函数",
            "公共层 · 部署文根（context root）自适应",
            "公共层 · 分页（服务端分页，对齐行内 PageVo 契约）",
            "公共层 · 通用渲染",
            "公共层 · 上游响应解析",
            "公共层 · 弹窗",
            "公共层 · 服务地址拼接",
            "模块 · 导航",
            "模块 · 模型管理",
            "模块 · 接入系统",
            "模块 · Drawer",
            "模块 · 凭证管理",
            "模块 · 接入示例（请求报文）",
            "模块 · 接入示例 · 连通性测试",
            "模块 · 模型授权",
            "模块 · 服务授权（接入系统 × 通用服务）",
            "模块 · 访问控制",
            "模块 · 审计日志 & 统计摘要",
            "模块 · 报文记录",
            "模块 · 网关配置",
            "模块 · 服务管理（通用服务接入 /svc/**）",
            "模块 · 模型测试工具",
            "模块 · Demo Data",
            "模块 · 初始化");

    /**
     * 必须定义在公共层里的函数。
     *
     * <p>判断标准不是"看起来通用"，而是<b>实际被两个以上模块调用</b>。
     * 新增这类函数时把名字加进来——加进来才有人替你守着它别被复制到模块里。
     */
    private static final List<String> COMMON_FUNCTIONS = List.of(
            "apiUrl", "defaultGatewayBase", "isOk", "errMsg",
            "applyPage", "renderPager", "fmt", "fmtTime", "toast", "setStatus",
            "escapeHtml", "escHtml", "jsArg", "statusTag", "copyText",
            "openModal", "closeModal",
            "extractModelAnswer", "extractUsageLabel",
            "svcBaseHasPath", "svcJoinUpstream");

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        if (!Files.exists(ADMIN_HTML)) {
            check("找得到 admin.html（需在项目根目录运行）", false);
            report();
            return;
        }
        String html = Files.readString(ADMIN_HTML, StandardCharsets.UTF_8);

        sectionsMatchExpected(html);
        commonFunctionsLiveInCommonLayer(html);
        noDuplicateTopLevelFunctions(html);
        responseParsersHandleSse(html);
        tocListsEverySection(html);

        report();
    }

    /** 分区标记齐全、格式统一、顺序与 SECTIONS 一致。 */
    private static void sectionsMatchExpected(String html) {
        List<String> actual = banners(html);
        check("分区数量一致（期望 " + SECTIONS.size() + "，实际 " + actual.size() + "）",
                actual.size() == SECTIONS.size());
        int n = Math.min(actual.size(), SECTIONS.size());
        for (int i = 0; i < n; i++) {
            check("第 " + (i + 1) + " 个分区 = " + SECTIONS.get(i),
                    SECTIONS.get(i).equals(actual.get(i)));
        }
        check("分区名不重复", new HashSet<>(actual).size() == actual.size());
    }

    /**
     * 公共函数必须定义在<b>第一个公共层标记</b>与<b>第一个模块标记</b>之间。
     *
     * <p>定义在模块里也能跑（函数声明提升），正因为能跑才需要机械检查——
     * 放错位置不报错，只是下一个人不知道它是公共的。
     */
    private static void commonFunctionsLiveInCommonLayer(String html) {
        int start = html.indexOf("// ===== 公共层 · ");
        int end = html.indexOf("// ===== 模块 · ");
        if (start < 0 || end < 0 || end <= start) {
            check("能定位公共层区间", false);
            return;
        }
        String common = html.substring(start, end);
        for (String fn : COMMON_FUNCTIONS) {
            Pattern def = Pattern.compile("(?m)^(function|const|let)\\s+" + Pattern.quote(fn) + "\\b");
            boolean inCommon = def.matcher(common).find();
            boolean anywhere = def.matcher(html).find();
            check(fn + " 定义在公共层内",
                    inCommon || failWithHint(fn, anywhere));
        }
    }

    /** 同名顶层函数出现两次 = 后一个静默覆盖前一个，改了其中一份还以为生效了。 */
    private static void noDuplicateTopLevelFunctions(String html) {
        List<String> names = new ArrayList<>();
        Matcher m = TOP_LEVEL_FN.matcher(html);
        while (m.find()) {
            names.add(m.group(1));
        }
        Set<String> seen = new HashSet<>();
        List<String> dup = new ArrayList<>();
        for (String n : names) {
            if (!seen.add(n)) {
                dup.add(n);
            }
        }
        check("没有重复定义的顶层函数" + (dup.isEmpty() ? "" : "，重复的是 " + dup), dup.isEmpty());
    }

    /**
     * 上游响应解析必须能处理 SSE 分帧，不能只会整串 {@code JSON.parse}。
     *
     * <p>流式响应的 usage 在<b>某一帧里</b>、正文在<b>逐帧的 delta 里</b>——
     * 只 parse 整串时两者都拿不到，表现是"回放/测试结果里 token 和正文都空着"，
     * 而这恰恰是流式排查最需要的两个数（{@code extractUsageLabel} 原先就漏了 SSE 这一半）。
     */
    private static void responseParsersHandleSse(String html) {
        for (String fn : new String[]{"extractModelAnswer", "extractUsageLabel"}) {
            int i = html.indexOf("function " + fn + "(");
            if (i < 0) {
                check(fn + " 存在", false);
                continue;
            }
            int end = html.indexOf("\n}", i);
            String body = html.substring(i, end < 0 ? Math.min(i + 1600, html.length()) : end);
            check(fn + " 有 SSE 分帧分支", body.contains("data:"));
            check(fn + " 跳过 [DONE] 标记", body.contains("[DONE]"));
        }
    }

    /** 文件头目录必须列全所有分区名，否则目录会慢慢变成一份过时的说明。 */
    private static void tocListsEverySection(String html) {
        int tocEnd = html.indexOf("// ===== 公共层 · ");
        String toc = tocEnd > 0 ? html.substring(0, tocEnd) : "";
        for (String s : SECTIONS) {
            // 目录里写的是短名（"模块 · 导航" 记作 "导航"）
            String shortName = s.substring(s.indexOf(" · ") + 3);
            check("目录里提到了「" + shortName + "」", toc.contains(shortName));
        }
    }

    private static List<String> banners(String html) {
        List<String> out = new ArrayList<>();
        Matcher m = BANNER.matcher(html);
        while (m.find()) {
            out.add(m.group(1) + " · " + m.group(2).trim());
        }
        return out;
    }

    /** 定义存在但不在公共层时，把"在哪儿"直接说出来，省一次翻文件。 */
    private static boolean failWithHint(String fn, boolean anywhere) {
        System.err.println("        ↑ " + fn
                + (anywhere ? " 定义在模块层里，请挪进公共层" : " 根本不存在，是不是改名了？"));
        return false;
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
