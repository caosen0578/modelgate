package com.pab.ficc.ibp.modelgate.server.common;

/**
 * 接口路径常量。
 *
 * <p>所有 Controller 的 URL 统一在此维护，便于全局检索、前后端对齐与规范约束。
 * 接口中的 {@code String} 常量隐含 {@code public static final}，是编译期常量，
 * 可直接用于 {@code @RequestMapping} / {@code @GetMapping} 等注解。
 *
 * <p>用法：类级基础路径引用 {@code ADMIN_XXX}，方法级子路径引用通用子路径常量，
 * 完整路径 = 基础路径 + 子路径。
 */
public interface UrlConstant {

    // ===== Controller 基础路径（class-level @RequestMapping）=====
    String ADMIN_MODELS      = "/admin/models";
    String ADMIN_APPS        = "/admin/apps";
    String ADMIN_CREDENTIALS = "/admin/credentials";
    String ADMIN_AUTH        = "/admin/auth";
    String ADMIN_ACCESS      = "/admin/access";
    String ADMIN_CONFIG      = "/admin/config";
    String ADMIN_STATS       = "/admin/stats";
    String ADMIN_AUDIT       = "/admin/audit";

    // ===== 通用子路径（method-level）=====
    String LIST         = "/list";
    String DETAIL       = "/detail";
    String ADD          = "/add";
    String CREATE       = "/create";
    String UPDATE       = "/update";
    String DELETE       = "/delete";
    String ENABLE       = "/enable";
    String DISABLE      = "/disable";
    String REVOKE       = "/revoke";
    String SAVE         = "/save";
    String TEST         = "/test";
    String USAGE        = "/usage";
    String UPSTREAM_URL = "/upstream/url";
}
