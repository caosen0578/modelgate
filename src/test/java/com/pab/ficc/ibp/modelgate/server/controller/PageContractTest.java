package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.domain.base.vo.PageVo;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分页返回结构的<b>前后端契约</b>回归用例（main 方式，不额外引入测试框架）。
 *
 * <p><b>为什么需要这个</b>：服务列表接口第一版手拼了一个 {@code {records, total, pageIndex, pageSize}}
 * 的 Map 返回，而前端 {@code applyPage} 读的是 {@code items}。后果是
 * {@code pv.items} 取到 undefined、列表恒空，偏偏 {@code total} 给对了——
 * 页面表现为<b>「顶上写着共 1 个服务、表格里一行没有」</b>，看起来像数据没存进数据库，
 * 排查方向会一路跑偏到 SQL 和事务上。
 *
 * <p>这类 bug 编译不报错、后端单测也测不出来（接口本身返回 200 且数据是对的），
 * 只有把两侧的字段名放在一起比对才能发现。所以这里直接读 {@code admin.html} 的源码，
 * 把前端实际读取的键与 {@code PageVo} 的实际字段做交叉断言。
 *
 * <p>加新列表接口时<b>一律返回 {@code PageVo}</b>，不要自己拼 Map——
 * 类型系统会替你保证字段名一致，这个用例只是最后一道兜底。
 */
public class PageContractTest {

    /** 前端 applyPage 读取的键；改这里之前先改 admin.html，两边必须同步 */
    private static final Set<String> FRONTEND_KEYS =
            Set.of("items", "total", "pageIndex", "pageSize", "pages");

    private static final Path ADMIN_HTML =
            Path.of("src", "main", "resources", "static", "admin.html");

    private static int pass;
    private static int fail;

    public static void main(String[] args) throws Exception {
        pageVoHasExactlyFrontendFields();
        applyPageReadsTheSameKeys();
        pageVoSerializesWithItemsKey();

        System.out.println();
        System.out.println("通过 " + pass + " / " + (pass + fail));
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** PageVo 的字段必须覆盖前端读的全部键——少一个前端就拿 undefined。 */
    private static void pageVoHasExactlyFrontendFields() {
        Set<String> voFields = java.util.Arrays.stream(PageVo.class.getDeclaredFields())
                .filter(f -> !f.isSynthetic())
                .map(java.lang.reflect.Field::getName)
                .collect(Collectors.toSet());

        for (String k : FRONTEND_KEYS) {
            check("PageVo 有字段 " + k, voFields.contains(k));
        }
        // getter 也要在——Jackson 按 getter 序列化，字段私有但没 getter 一样出不去
        for (String k : FRONTEND_KEYS) {
            String getter = "get" + Character.toUpperCase(k.charAt(0)) + k.substring(1);
            boolean has = java.util.Arrays.stream(PageVo.class.getMethods())
                    .map(Method::getName).anyMatch(getter::equals);
            check("PageVo 有 " + getter + "()", has);
        }
    }

    /**
     * 直接读 admin.html 的 applyPage 源码，确认它读的就是这几个键。
     *
     * <p>反过来钉：若哪天有人在前端改成读 {@code records}，这条会失败并提示两边要一起改。
     */
    private static void applyPageReadsTheSameKeys() throws IOException {
        if (!Files.exists(ADMIN_HTML)) {
            check("找得到 admin.html（需在项目根目录运行）", false);
            return;
        }
        String html = Files.readString(ADMIN_HTML, StandardCharsets.UTF_8);
        int i = html.indexOf("function applyPage(");
        if (i < 0) {
            check("admin.html 里存在 applyPage", false);
            return;
        }
        // 取函数体：从 function 到下一个顶格 '}' 为止，够用且不引入解析器
        int end = html.indexOf("\n}", i);
        String body = html.substring(i, end < 0 ? Math.min(i + 800, html.length()) : end);

        for (String k : FRONTEND_KEYS) {
            check("applyPage 读了 pv." + k, body.contains("pv." + k));
        }
        // 反例：不该出现 records —— 那正是当初写错的键
        check("applyPage 不读 pv.records（写错过一次）", !body.contains("pv.records"));
    }

    /**
     * 真的走一遍 PageVo.of + Jackson 序列化，确认 JSON 里出来的是 {@code items} 而不是别的。
     *
     * <p>只做反射断言是不够的：字段名对了但 Jackson 有 {@code @JsonProperty} 改名的话，
     * 前端照样拿不到。这里端到端验一次序列化结果。
     */
    private static void pageVoSerializesWithItemsKey() throws Exception {
        Page<String> page = new Page<>(2, 20);
        page.setRecords(List.of("a", "b"));
        page.setTotal(41);
        page.setPages(3);

        PageVo<String> vo = PageVo.of(page, x -> x.toUpperCase());
        JsonNode json = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(vo));

        check("JSON 里有 items 数组",
                json.has("items") && json.get("items").isArray() && json.get("items").size() == 2);
        check("items 内容经过了映射函数", "A".equals(json.get("items").get(0).asText()));
        check("JSON 里没有 records 键", !json.has("records"));
        check("total 透传", json.path("total").asLong() == 41L);
        check("pageIndex 透传", json.path("pageIndex").asLong() == 2L);
        check("pageSize 透传", json.path("pageSize").asLong() == 20L);
        check("pages 透传（renderPager 用它算总页数）", json.path("pages").asLong() == 3L);
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
