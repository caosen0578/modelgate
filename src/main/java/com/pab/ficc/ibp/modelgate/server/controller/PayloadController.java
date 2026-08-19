package com.pab.ficc.ibp.modelgate.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pab.ficc.ibp.domain.base.vo.PageVo;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.PayloadQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.RequestPayloadEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PayloadDetailVO;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PayloadListVO;
import com.pab.ficc.ibp.modelgate.server.mapper.RequestPayloadMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 请求/响应报文查询接口。
 *
 * <p>与审计日志的分工：{@code mg_audit_log} 回答"这次调用成不成功、花了多久、烧了多少 token"，
 * 本接口回答"这次调用到底发了什么、上游回了什么"。两者通过 {@code traceId} 关联，
 * 页面上可从审计行一键跳到对应报文。
 *
 * <p><b>列表与详情严格分离</b>：列表不返回三个 LONGTEXT 正文（见
 * {@code RequestPayloadMapper.selectListPage} 注释），正文只在详情接口按 id 单条返回。
 *
 * <ul>
 *   <li>GET /modelgate/payload        — 分页列表，多条件检索</li>
 *   <li>GET /modelgate/payload/detail — 单条全文</li>
 *   <li>GET /modelgate/payload/meta   — 保留期等配置回显，页面据此提示"只能查最近 N 天"</li>
 * </ul>
 */
@Tag(name = "报文记录", description = "查询模型调用的请求/响应报文，支持按系统、模型、traceId、时间、正文关键字检索")
@RestController
@RequestMapping(UrlConstant.MG_PAYLOAD)
@RequiredArgsConstructor
public class PayloadController {

    private final RequestPayloadMapper payloadMapper;

    @Value("${modelgate.payload-log.enabled:true}")
    private boolean payloadLogEnabled;

    @Value("${modelgate.payload-log.retention-days:7}")
    private int retentionDays;

    @Value("${modelgate.payload-log.cleanup-cron:0 30 2 * * ?}")
    private String cleanupCron;

    @Value("${modelgate.payload-log.max-capture-bytes:67108864}")
    private long maxCaptureBytes;

    @Operation(summary = "分页查询报文记录（不含正文全文；pageIndex 从 1 开始，按时间倒序）")
    @GetMapping(UrlConstant.ROOT)
    public Mono<Result<PageVo<PayloadListVO>>> query(PayloadQueryBO query) {
        PayloadQueryBO q = query != null ? query : new PayloadQueryBO();
        // 校验放在 Mono 外：参数错误应当立刻返回，不必占用 boundedElastic 线程
        validate(q);
        return Mono.fromCallable(() -> toPageVo(doQuery(q)))
                .subscribeOn(ADMIN_QUERY_SCHEDULER)
                .timeout(REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "查询单条报文全文（含请求报文、响应原文、响应正文）")
    @GetMapping(UrlConstant.DETAIL)
    public Mono<Result<PayloadDetailVO>> detail(
            @Parameter(description = "报文记录主键 id") @RequestParam(name = "id") Long id) {
        return Mono.fromCallable(() -> {
                    PayloadDetailVO vo = payloadMapper.selectDetailById(id);
                    if (vo == null) {
                        // 报文保留期只有几天，查不到多半是已被清理而非 id 写错，提示要说清楚
                        throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                                "报文不存在或已过保留期被清理: id=" + id);
                    }
                    return vo;
                })
                .subscribeOn(ADMIN_QUERY_SCHEDULER)
                .timeout(REQUEST_TIMEOUT)
                .map(Result::success);
    }

    @Operation(summary = "报文记录功能的配置回显（总开关 / 保留天数 / 清理时间 / 采集上限）")
    @GetMapping(UrlConstant.META)
    public Mono<Result<Map<String, Object>>> meta() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("enabled", payloadLogEnabled);
        m.put("retentionDays", retentionDays);
        m.put("cleanupCron", cleanupCron);
        m.put("maxCaptureBytes", maxCaptureBytes);
        return Mono.just(Result.success(m));
    }

    /** 单页上限。报文行带正文预览与跨表子查询，比普通列表贵得多，上限压得比常规更狠。 */
    private static final long MAX_PAGE_SIZE = 200;

    /**
     * 最大可翻到的 OFFSET（= (pageIndex-1) × pageSize）。
     *
     * <p>这是本轮审查里最反直觉的一条：所有防护原先都挂在 keyword 分支上，
     * 但<b>一次不带任何条件的深翻页同样会全表扫描</b>，而且 UI 上就有一个按钮直接触发它——
     * 分页条的「末页」精确命中最大合法 OFFSET。
     *
     * <p>机制：{@code LIMIT n OFFSET N} 里被跳过的 N 行仍然要从聚簇索引读出来；
     * 而 {@code request_body}/{@code response_text} 因为被 {@code LEFT}/{@code CHAR_LENGTH}
     * 引用而进了 read_set，InnoDB 会为<b>每一条跳过的行</b>物化行外存储的完整 LONGTEXT
     * （见 {@code RequestPayloadMapper.selectListPage} 注释）。代价 ≈ N × 单行正文大小。
     * 保留期 7 天、每天 2 万次调用 → 14 万行，末页 OFFSET≈14 万 × ~20KB ≈ 2.8GB，一次点击。
     *
     * <p>5 万这个值：按 pageSize=20 算是第 2500 页。真要翻到那么后面，正确做法是用时间范围
     * 跳过去，而不是一页页翻——所以超限时的提示直接引导去填时间范围。
     */
    private static final long MAX_OFFSET = 50_000;

    /**
     * 管理面查询专用线程池。
     *
     * <p><b>不能用 {@code Schedulers.boundedElastic()}</b>：那是 Reactor 的<b>全局共享单例</b>，
     * 网关热路径上的 {@code CredentialAuthFilter} / {@code ModelAuthFilter} /
     * {@code AppAccessControlFilter} / {@code QuotaFilter} / {@code ModelRouteFilter}
     * 全都在用它做阻塞 DB 调用。它默认 {@code maxThreads = 10 × CPU 核数}
     * （4 核容器 = 40），比 Druid 的 maxActive=100 更早被打满。
     *
     * <p>更要命的是失败模式：Druid 拿不到连接会在 maxWait=3000ms 后 fail-fast 抛异常，
     * 而 boundedElastic 的队列深度是 100000 且<b>没有超时</b>——线程占满后网关的鉴权任务
     * 只是安静地排队，不报错、不降级，表现为 {@code /v1/**} 整体延迟抬升而日志干净，
     * 极难归因。几个人同时在报文页做慢查询就足以触发。
     *
     * <p>所以这里单开一个小池子：管理面最多同时跑 {@value #ADMIN_POOL_SIZE} 个查询，
     * 排队上限 {@value #ADMIN_POOL_QUEUE}，<b>再慢也烧不到网关的线程</b>。
     * 队列满时直接拒绝（抛 RejectedExecutionException），这正是我们要的——
     * 管理面查询失败远好过网关转发被拖慢。
     */
    private static final int ADMIN_POOL_SIZE = 4;
    private static final int ADMIN_POOL_QUEUE = 64;
    private static final Scheduler ADMIN_QUERY_SCHEDULER =
            Schedulers.newBoundedElastic(ADMIN_POOL_SIZE, ADMIN_POOL_QUEUE, "mg-admin-payload");

    /**
     * 整个请求的兜底超时。
     *
     * <p>比 {@code RequestPayloadMapper.QUERY_TIMEOUT_SECONDS}（30s，JDBC statement 级）
     * 稍长：正常情况该由 statement 超时先掐掉并给出 SQL 层的错误，
     * 这一层只兜"连 statement 超时都没生效"的情况（比如卡在拿连接而不是执行）。
     */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(35);

    /**
     * {@code truncated} 的合法取值。
     *
     * <p>只有这两个：{@code PayloadService.toEntity} 写的是
     * {@code truncated ? "response" : "none"}，请求侧根本没有截断机制
     * （{@code BodyParseFilter} 用 {@code DataBufferUtils.join} 一次性读全请求体，无字节上限）。
     * DDL 注释与 04 文档写的四态 {@code none|request|response|both} 是<b>从未兑现的契约</b>。
     * 传 {@code request}/{@code both} 会得到 200 + 空列表，让人误以为"这段时间没有请求被截断"，
     * 所以这里显式拒绝，让不合法的取值<b>响亮地失败</b>而不是安静地骗人。
     */
    private static final Set<String> VALID_TRUNCATED = Set.of("none", "response");

    /**
     * 入参校验。
     *
     * <p>三件事，每件都对应一条能把数据库拖垮或把人误导的真实路径：
     * <ol>
     *   <li><b>关键字必须搭配收窄条件</b>——正文检索是 LONGTEXT 上的 {@code LIKE '%x%'}，
     *       无法走索引。单独使用就是一轮全表扫描 + 把全部正文读进 buffer pool，
     *       连带把网关热数据（凭证/授权等小表）挤出缓冲池，这是"管理面拖垮转发"最隐蔽的路径。</li>
     *   <li><b>pageSize 必须封顶</b>——{@code BaseBo.setPageSize} 是个裸 setter，不做任何收敛。
     *       实测 {@code pageSize=5000} 会被原样接受。一个 GET 就能让 MySQL 吐出几千行带正文预览
     *       的结果并长时间占住一条 Druid 连接（池上限 100），是最廉价的自伤方式。</li>
     *   <li><b>truncated 取值白名单</b>——理由见 {@link #VALID_TRUNCATED}。
     *       {@code finishReason} <b>不做</b>白名单：它的取值由上游模型决定，
     *       新模型随时可能返回新值，写死白名单会把合法查询拒掉。</li>
     * </ol>
     */
    private void validate(PayloadQueryBO q) {
        String keyword = q.getKeyword();
        if (keyword != null && !keyword.isBlank() && !q.hasNarrowingFilter()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, String.format(
                    "正文关键字检索必须同时指定：TraceID（至少 %d 位前缀），"
                            + "或两端齐全且跨度不超过 %d 小时的时间范围。"
                            + "（接入系统/模型不足以收窄——单个系统常占全表大半，"
                            + "关键字是 LONGTEXT 上的模糊匹配走不了索引，候选集不设上界会拖垮数据库）",
                    PayloadQueryBO.tracePrefixMin(), PayloadQueryBO.timeSpanMaxHours()));
        }

        if (q.getPageSize() <= 0 || q.getPageIndex() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "pageIndex 与 pageSize 必须为正数");
        }
        if (q.getPageSize() > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "pageSize 不能超过 " + MAX_PAGE_SIZE + "，当前传入 " + q.getPageSize()
                            + "（报文行含正文预览与跨表 Token 查询，单页过大会长时间占用数据库连接）");
        }
        // 深翻页与 keyword 无关，是另一条同样能全表扫的路径，且 UI 上的「末页」按钮直接命中
        long offset = (q.getPageIndex() - 1) * q.getPageSize();
        if (offset > MAX_OFFSET) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, String.format(
                    "翻页过深（已跳过 %d 条，上限 %d）。请改用时间范围直接定位到目标时段，"
                            + "不要一页页翻——被跳过的每一行都要把正文从磁盘读出来，代价与跳过的行数成正比",
                    offset, MAX_OFFSET));
        }

        String truncated = q.getTruncated();
        if (truncated != null && !truncated.isBlank() && !VALID_TRUNCATED.contains(truncated)) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "truncated 取值非法: " + truncated + "；当前系统只会写入 " + VALID_TRUNCATED
                            + "（请求侧无截断机制，request/both 永远不会出现）");
        }
    }

    /**
     * 按条件分页查报文列表。
     *
     * <p>排序用 {@code create_time DESC, id DESC}：报文与审计一样是高频追加表，
     * 同一毫秒可写入多条，只按 create_time 排会在翻页时出现重复行/漏行，
     * 必须补一个唯一列做次级排序。
     */
    private IPage<PayloadListVO> doQuery(PayloadQueryBO q) {
        String appId        = q.getAppId();
        String modelId      = q.getModelId();
        String traceId      = q.getTraceId();
        String finishReason = q.getFinishReason();
        String truncated    = q.getTruncated();
        String keyword      = q.getKeyword();

        LambdaQueryWrapper<RequestPayloadEntity> w = new LambdaQueryWrapper<RequestPayloadEntity>()
                .eq(notBlank(appId),        RequestPayloadEntity::getAppId,          appId)
                .eq(notBlank(modelId),      RequestPayloadEntity::getModelId,        modelId)
                .eq(notBlank(finishReason), RequestPayloadEntity::getFinishReason,   finishReason)
                .eq(notBlank(truncated),    RequestPayloadEntity::getTruncated,      truncated)
                .eq(q.getIsStream() != null,       RequestPayloadEntity::getIsStream,       q.getIsStream())
                .eq(q.getUpstreamStatus() != null, RequestPayloadEntity::getUpstreamStatus, q.getUpstreamStatus())
                .ge(q.getStartTime() != null,      RequestPayloadEntity::getCreateTime,     q.getStartTime())
                .le(q.getEndTime() != null,        RequestPayloadEntity::getCreateTime,     q.getEndTime());

        // traceId 按前缀匹配：列表展示的是截断值（前 12 字符），全等的话用户复制界面上看到的
        // 内容来筛选永远查不到。前缀匹配在 idx_trace_id 上是范围扫描，索引仍然生效。
        if (notBlank(traceId)) {
            w.apply("trace_id LIKE {0} ESCAPE '" + LIKE_ESCAPE + "'",
                    escapeLike(traceId.trim()) + "%");
        }

        if (notBlank(keyword)) {
            // 请求与响应都匹配：排查既可能从"用户问了什么"入手，也可能从"模型答了什么"入手。
            // 只搜 response_text 不搜 response_raw —— raw 是 SSE 分帧原文，同一句话被切碎在多帧里，
            // LIKE 匹配不到完整词，搜它只会给出误导性的空结果。
            String kw = "%" + escapeLike(keyword.trim()) + "%";
            w.and(x -> x.apply("request_body LIKE {0} ESCAPE '"  + LIKE_ESCAPE + "'", kw)
                        .or()
                        .apply("response_text LIKE {0} ESCAPE '" + LIKE_ESCAPE + "'", kw));
        }

        w.orderByDesc(RequestPayloadEntity::getCreateTime)
         .orderByDesc(RequestPayloadEntity::getId);

        return payloadMapper.selectListPage(new Page<>(q.getPageIndex(), q.getPageSize()), w);
    }

    /** 列表 VO 已是最终形态，无需再映射，这里只做 PageVo 包装。 */
    private static PageVo<PayloadListVO> toPageVo(IPage<PayloadListVO> page) {
        return PageVo.of(page, v -> v);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /**
     * LIKE 的转义字符。
     *
     * <p><b>刻意不用反斜杠。</b>反斜杠在 MySQL 里是"默认"转义符，但这个默认<b>可以被关掉</b>：
     * {@code sql_mode} 含 {@code NO_BACKSLASH_ESCAPES} 时（对齐 ANSI 兼容时很常见）
     * 根本不存在默认转义符，此前写的 {@code \%} 会退化成"字面反斜杠 + 通配符"，
     * 两个方向的失效都很难察觉：正常关键字（如 {@code 100%}、{@code user_id}）匹配不到任何行
     * 而返回空列表（看起来像"没有这条报文"），单个 {@code %} 又变回大范围匹配。
     * 而本项目的 JDBC URL 没有用 {@code sessionVariables} 固定 {@code sql_mode}，
     * 服务端配什么就是什么，代码这边没有任何保障。
     *
     * <p>改用 {@code !} 并<b>显式写 {@code ESCAPE '!'}</b> 子句，语义与 {@code sql_mode} 完全无关。
     */
    private static final String LIKE_ESCAPE = "!";

    /**
     * 转义 LIKE 的通配符。
     *
     * <p>为什么必须转义：MyBatis-Plus 的 {@code like()} 只负责把值包成 {@code %值%}，
     * <b>不转义</b>值里的 {@code %} 和 {@code _}。不处理的话，关键字填一个 {@code %}
     * 就变成 {@code LIKE '%%%'}——匹配全部行，把"必须先收窄"的防护整个绕过去，
     * 等于一次点击换一轮全表扫 LONGTEXT（实测曾返回全表 2735 条）。
     * 这不是 SQL 注入（值始终是绑定参数），是通配符被当成语法解释。
     *
     * <p>转义符本身要先转义，否则关键字里的 {@code !} 会把后一个字符吃掉。
     */
    private static String escapeLike(String s) {
        return s.replace(LIKE_ESCAPE, LIKE_ESCAPE + LIKE_ESCAPE)
                .replace("%", LIKE_ESCAPE + "%")
                .replace("_", LIKE_ESCAPE + "_");
    }
}
