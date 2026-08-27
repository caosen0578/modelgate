package com.pab.ficc.ibp.modelgate.server.common;

import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import org.springframework.web.server.ServerWebExchange;

/**
 * 流量类型：整条过滤器链据此分流。
 *
 * <p><b>为什么要有这个东西</b>：改造前每个过滤器各自做 1~3 次
 * {@code path.startsWith(...)}（多数是"管理面 + actuator"两次，
 * ModelRouteFilter 最多、三个前缀都比），8 个过滤器合计 <b>15 次</b>前缀比较。
 * 更要命的是判定规则散落在各处，加一类流量（如 {@code /svc/**}）就要逐个文件改，
 * 漏改一处的表现是"某类流量被错误的过滤器拦截或误放行"——极难定位。
 *
 * <p>改为在链路最前面（{@code TraceFilter}，order=-200，第一个 GlobalFilter）
 * <b>算一次</b>存进 exchange attribute，后续过滤器一律读 attribute。
 *
 * <p><b>使用约定</b>：过滤器里<b>禁止</b>再出现任何路径字面量或 {@code startsWith} 判断，
 * 一律用 {@link #resolve(ServerWebExchange)} 配合 {@link #isBusiness()} / {@code == MODEL}。
 * 这与 {@code UrlConstant.BASE} 的既有约束同性质：判定规则必须只有一个出处。
 */
public enum TrafficType {

    /** 模型流量 {@code /v1/**}：路由键在请求体的 {@code model} 字段，必须读全 body */
    MODEL,

    /** 通用服务流量 {@code /svc/{服务编码}/**}：路由键在 path，不读 body */
    SERVICE,

    /**
     * 管理面 {@code /modelgate/**} 与运维端点 {@code /actuator/**}：<b>唯一</b>不走业务过滤器的一类。
     *
     * <p>正常情况下根本不会出现——GlobalFilter 只对匹配到网关路由的请求执行，
     * 而管理面与 actuator 都不匹配任何路由，压根进不到这条链上。
     * 保留这个取值是防御性的：万一将来加了更宽的路由 predicate，
     * 业务过滤器也不会误伤管理接口。
     */
    INTERNAL,

    /**
     * 认不出来的路径：<b>按业务流量处理（fail-closed）</b>。
     *
     * <p><b>这个取值的存在是为了修一个真实的鉴权绕过，删掉它之前请先读完这段。</b>
     *
     * <p>改造前各通用过滤器用的是<b>黑名单</b>——「只跳过 {@code /modelgate/} 与
     * {@code /actuator/}，其余一律执行」。本次重构一度把它写成<b>白名单</b>
     * （「只对 MODEL / SERVICE 执行」），并让认不出的路径落到 {@link #INTERNAL}。
     * 这两者对"标准形状"的路径等价，但对下面这些<b>路由能匹配、前缀判定却匹配不上</b>的路径不等价：
     *
     * <pre>
     *   路径                        Path=/v1/** 匹配   URI.getPath().startsWith("/v1/")
     *   /v1                          true              false     ← ** 可匹配零个段
     *   /v1;a=b                      true              false     ← 矩阵参数
     *   /v1;a=b/chat/completions     true              false     ← 同上
     * </pre>
     *
     * <p>（以上为实测结果，用 {@code PathPatternParser.parse("/v1/**")} 对
     * {@code PathContainer.parsePath(...)} 逐条跑出来的：路由谓词走的是 PathContainer，
     * 会逐段剥离矩阵参数；而 {@code URI.getPath()} 把 {@code ;a=b} 原样留在字符串里。）
     *
     * <p>这些请求<b>进得到过滤器链</b>。判成 INTERNAL 的后果是凭证鉴权、访问控制、配额、
     * 熔断<b>全部被跳过</b>，请求仍被转发到路由的上游地址——一个匿名请求就这样被代理出去了。
     * 而改造前它们会正常走到 CredentialAuthFilter 并因缺少 Authorization 返回 401。
     *
     * <p>因此兜底必须落在这里、且 {@link #isBusiness()} 对它返回 {@code true}，
     * 让语义退回改造前的黑名单：<b>只有明确是管理面/运维端点才跳过，其余一概鉴权</b>。
     *
     * <p>注意 {@code == MODEL} 的模型专属判定不受影响——{@code /v1} 与 {@code /v1;a=b/...}
     * 在改造前同样不满足 {@code startsWith("/v1/")}、同样跳过 BodyParse/ModelRoute，
     * 现在判成 UNKNOWN 后行为完全一致。<b>这是有意保持的</b>：把它们改判成 MODEL
     * 虽然更"正确"，但那是行为变更，超出本批次"纯重构"的范围，另见 12 §12.3 的登记。
     */
    UNKNOWN;

    /** exchange attribute key，值为本枚举实例 */
    public static final String ATTR = "mg.trafficType";

    /**
     * 取本次请求的流量类型；attribute 不存在时就地计算并写回。
     *
     * <p>稳态下由 {@code TraceFilter}(-200) 第一个调用并写入，后续过滤器都走
     * "读 attribute 直接返回"这条路，全链路只做一次前缀比较。
     *
     * <p>之所以带"缺失时自行计算"的回退而不是直接假定 attribute 一定在：
     * 这样任何一个过滤器单独被测试、或 order 被调整到 -200 之前时都仍然正确，
     * 不会因为拿到 null 而静默走错分支。代价只是一次 {@code startsWith}。
     */
    public static TrafficType resolve(ServerWebExchange exchange) {
        TrafficType cached = exchange.getAttribute(ATTR);
        if (cached != null) {
            return cached;
        }
        TrafficType resolved = of(exchange.getRequest().getURI().getPath());
        exchange.getAttributes().put(ATTR, resolved);
        return resolved;
    }

    /**
     * 按路径判定流量类型。
     *
     * <p><b>判定顺序不可调换</b>：{@link #INTERNAL} 必须最先判——
     * 管理面前缀是 {@code /modelgate/}、actuator 是 {@code /actuator/}，
     * 与业务前缀不重叠，但把它放最后意味着"新增业务前缀时可能意外覆盖管理路径"，
     * 先判内部是更安全的写法。
     *
     * <p><b>兜底返回 {@link #UNKNOWN} 而不是 {@link #INTERNAL}</b>——这一条是安全属性，
     * 不要"顺手"改回去。理由见 {@link #UNKNOWN} 的注释：路由谓词 {@code Path=/v1/**}
     * 的接受集比 {@code startsWith("/v1/")} 更宽（{@code /v1}、{@code /v1;a=b/...}），
     * 落到 INTERNAL 会让这些能进链的请求跳过全部鉴权。
     */
    static TrafficType of(String path) {
        if (path.startsWith(UrlConstant.MANAGE_PREFIX) || path.startsWith(UrlConstant.ACTUATOR_PREFIX)) {
            return INTERNAL;
        }
        if (path.startsWith(UrlConstant.BUSINESS_PREFIX)) {
            return MODEL;
        }
        if (path.startsWith(UrlConstant.SERVICE_PREFIX)) {
            return SERVICE;
        }
        return UNKNOWN;
    }

    /**
     * 是否为业务流量（需要走凭证鉴权、访问控制、配额、熔断、审计）。
     *
     * <p><b>实现必须是 {@code != INTERNAL}（黑名单），不能写成
     * {@code == MODEL || == SERVICE}（白名单）</b>——这两者对认不出的路径结果相反，
     * 而"认不出"里恰好包含 {@code /v1}、{@code /v1;a=b/...} 这些能被路由匹配、
     * 会真的进到过滤器链的路径。写成白名单等于给它们发免鉴权通行证，
     * 详见 {@link #UNKNOWN}。
     *
     * <p>这个表达式与改造前各过滤器的
     * {@code !(path.startsWith("/modelgate/") || path.startsWith("/actuator/"))}
     * <b>逐路径等价</b>，这正是本批次"纯重构、零行为改变"能成立的依据。
     *
     * <p>通用能力过滤器（CredentialAuth / AppAccessControl / Quota / PayloadCapture /
     * CircuitBreaker）用这个判断；模型专属的（BodyParse / ModelAuth / ModelRoute）
     * 直接比 {@code == MODEL}，同样与改造前的 {@code startsWith("/v1/")} 逐路径等价。
     */
    public boolean isBusiness() {
        return this != INTERNAL;
    }
}
