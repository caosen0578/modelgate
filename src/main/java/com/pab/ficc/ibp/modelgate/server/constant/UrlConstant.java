package com.pab.ficc.ibp.modelgate.server.constant;

/**
 * 接口路径常量。
 *
 * <p>所有 Controller 的 URL 统一在此维护，便于全局检索、前后端对齐与规范约束。
 * 接口中的 {@code String} 常量隐含 {@code public static final}，是编译期常量
 * （常量间的字符串拼接同样是编译期常量），可直接用于 {@code @RequestMapping} / {@code @GetMapping} 等注解。
 *
 * <p>用法：类级基础路径引用 {@code MG_XXX}，方法级子路径引用通用子路径常量，
 * 完整路径 = 基础路径 + 子路径。
 */
public interface UrlConstant {

    // ===== 路径前缀 =====
    /**
     * 管理面基础路径。
     *
     * <p>这个前缀是**功能性**的，不只是命名：整条过滤器链靠它区分"管理接口"与"业务流量"——
     * 业务流量走 {@link #BUSINESS_PREFIX}，会经过凭证鉴权、模型授权、配额限流、熔断、路由转发；
     * 管理接口必须跳过这些。各过滤器一律引用本常量判断，**禁止**再写 {@code "/modelgate/"} 字面量，
     * 否则改前缀时漏改一处就会造成管理接口被业务过滤器拦截或误放行。
     */
    String BASE = "/modelgate";

    /** 管理面前缀（带尾斜杠），用于 {@code path.startsWith(...)} 判断 */
    String MANAGE_PREFIX = BASE + "/";

    /** 业务流量前缀：OpenAI 兼容路径，形状由 OpenAI SDK 决定，不可加命名空间 */
    String BUSINESS_PREFIX = "/v1/";

    /** 运维端点前缀，与管理面同等待遇（不走业务过滤器） */
    String ACTUATOR_PREFIX = "/actuator/";

    // ===== Controller 基础路径（class-level @RequestMapping）=====
    String MG_MODELS      = BASE + "/models";
    String MG_APPS        = BASE + "/apps";
    String MG_CREDENTIALS = BASE + "/credentials";
    String MG_AUTH        = BASE + "/auth";
    String MG_ACCESS      = BASE + "/access";
    String MG_CONFIG      = BASE + "/config";
    String MG_STATS       = BASE + "/stats";
    String MG_AUDIT       = BASE + "/audit";
    String MG_PAYLOAD     = BASE + "/payload";

    // ===== 通用子路径（method-level）=====
    /** 基础路径本身（等价于省略 value 的 @GetMapping/@PostMapping），用于显式声明"打在类级路径上" */
    String ROOT         = "";
    String LIST         = "/list";
    String DETAIL       = "/detail";
    String ADD          = "/add";
    String CREATE       = "/create";
    String UPDATE       = "/update";
    String DELETE       = "/delete";
    String ENABLE       = "/enable";
    String DISABLE      = "/disable";
    String REVOKE       = "/revoke";
    /** 查看凭证明文（单条按需解密，禁止用于列表） */
    String SECRET       = "/secret";
    /** 反查某模型的授权系统列表 */
    String APPS         = "/apps";
    String SAVE         = "/save";
    String TEST         = "/test";
    /** 管理面对话测试：服务端用库中模型凭证直连上游，密钥不出服务端 */
    String CHAT_TEST    = "/chat/test";
    String USAGE        = "/usage";
    /** 功能自身的配置回显（如报文记录的保留期/清理时间），供页面展示"数据能查多久" */
    String META         = "/meta";
}
