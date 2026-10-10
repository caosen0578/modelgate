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
    /** 应用内业务路径；部署文根由 spring.webflux.base-path 提供，不写进注解。 */
    String BUSINESS_PREFIX = "/v1/";

    /**
     * 通用服务流量前缀（带尾斜杠），形如 {@code /svc/{服务编码}/**}。
     *
     * <p>与 {@link #BUSINESS_PREFIX} 并列的第二类业务流量：接入系统的普通 HTTP / SSE 接口
     * 借本网关做鉴权与配额，路由目标来自 {@code mg_service.upstream_url}。
     *
     * <p><b>为什么必须是静态前缀</b>：网关要在**读请求体之前**就分辨流量类型——
     * 模型流量的路由键藏在 body 的 {@code model} 字段里、必须整体读入，
     * 而服务流量的路由键就在 path 上、一个字节的 body 都不用碰。
     * 没有可在请求行阶段判定的前缀，就只能对所有流量先读 body 再判断，
     * 等于把服务流量的成本拉到和模型一样高。
     *
     * <p>判定一律走 {@code TrafficType}，<b>禁止</b>在过滤器里再写路径字面量。
     */
    String SERVICE_PREFIX = "/svc/";

    /** 运维端点前缀，与管理面同等待遇（不走业务过滤器） */
    String ACTUATOR_PREFIX = "/actuator/";

    // ===== 行内容器平台探测端点 =====
    /**
     * 容器平台探测基础路径。
     *
     * <p>应用内路径由<b>行内容器平台约定</b>。部署文根由框架统一添加，平台探针需同步：
     * 当前外部入口为 {@code /modelgate/actuator/detection/**}。平台分别调用
     * {@link #DETECTION_RUNNING} / {@link #DETECTION_AVAILABLE} / {@link #DETECTION_SHUTDOWN}
     * 判断应用状态，拿不到 200 就认为应用没起来并反复重启容器。
     *
     * <p>放在 {@link #ACTUATOR_PREFIX} 之下是有意的：六个业务过滤器都按该前缀跳过，
     * 探测请求因此天然不经过凭证鉴权/授权/配额/熔断/路由，不需要为它们单独开白名单。
     */
    String MG_DETECTION = "/actuator/detection";

    /** 存活检查：进程还在就返回 200，<b>绝不依赖 DB/Redis</b>（理由见 ContainerDetectionController） */
    String DETECTION_RUNNING = "/running";
    /** 流量检查：启动完成且未进入优雅退出流程时返回 200，否则 503 */
    String DETECTION_AVAILABLE = "/available";
    /** 优雅退出：置为「不再接流量」并返回 200，随后平台摘流量、再发 SIGTERM */
    String DETECTION_SHUTDOWN = "/shutdown";

    // ===== Controller 基础路径（class-level @RequestMapping）=====
    String MG_MODELS      = "/models";
    String PROVIDER_ADAPTERS = "/provider-adapters";
    /** 服务管理（通用服务注册），与 MG_MODELS 平级 */
    String MG_SERVICES    = "/services";
    /** 服务授权（接入系统 × 服务），与 MG_AUTH 平级 */
    String MG_SERVICE_AUTH = "/service-auth";
    String MG_APPS        = "/apps";
    String MG_CREDENTIALS = "/credentials";
    String MG_AUTH        = "/auth";
    String MG_ACCESS      = "/access";
    String MG_CONFIG      = "/config";
    String MG_STATS       = "/stats";
    String MG_AUDIT       = "/audit";
    String MG_PAYLOAD     = "/payload";

    /** 明确列举内部资源；绝不能用空 BASE + '/'，否则全部业务路径会被免鉴权。 */
    java.util.List<String> MANAGE_PATHS = java.util.List.of(MG_MODELS, MG_SERVICES,
        MG_SERVICE_AUTH, MG_APPS, MG_CREDENTIALS, MG_AUTH, MG_ACCESS, MG_CONFIG,
        MG_STATS, MG_AUDIT, MG_PAYLOAD);

    // ===== 通用子路径（method-level）=====
    //
    // 【硬性规则】每个接口都必须显式声明子路径，且<b>子路径与方法名一致</b>
    //（方法叫 page，路径就是 /page）。
    //
    // 禁止两种写法：
    //   ① 裸注解 @GetMapping —— 路径隐含为类级路径，从方法上完全看不出 URL
    //   ② 空常量 @GetMapping(ROOT) —— 同上，只是把"省略"包装了一层，同样看不出 URL
    // 原先有一个 ROOT = "" 专门干这事，2026-09-10 已删除：它让
    // 「GET /modelgate/models」和「POST /modelgate/models」在方法签名上毫无区别，
    // 全局搜 URL 搜不到、看方法猜不出，维护成本远高于多写一个词。
    String LIST         = "/list";
    /** 分页查询（与 LIST 的区别：返回 PageVo，带 pageIndex/pageSize） */
    String PAGE         = "/page";
    /** 条件查询（语义上比 LIST 更强调"按条件筛"，如审计日志、报文记录） */
    String QUERY        = "/query";
    /** 全量查询（不分页、不筛选，如网关配置项） */
    String FIND_ALL     = "/find-all";
    String DETAIL       = "/detail";
    String ADD          = "/add";
    String CREATE       = "/create";
    String UPDATE       = "/update";
    String DELETE       = "/delete";
    /**
     * 启用/禁用二合一。
     *
     * <p>2026-09-10 由 {@code /enable} + {@code /disable} 两个接口合并而来：
     * 这是<b>同一个状态字段的两个取值</b>，拆成两个 URL 只是把参数编进了路径，
     * 调用方（管理面与 static-framework）本来就得写
     * {@code `/models/${enabled ? 'disable' : 'enable'}`} 这种拼串，
     * 拼错了还只能等 404。合并后统一为 {@code ?enabled=true|false}，
     * 前端传布尔值即可，后端也少一半的方法。
     */
    String TOGGLE       = "/toggle";
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
    /** 报文回放：把留档的那一发请求重新打给上游 */
    String REPLAY       = "/replay";
    /** 某服务最近被实际调用过的入站路径，供「接入示例」带出接口路径 */
    String RECENT_PATHS = "/recent-paths";
}
