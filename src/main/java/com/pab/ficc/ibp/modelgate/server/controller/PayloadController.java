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
import reactor.core.scheduler.Schedulers;

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
                .subscribeOn(Schedulers.boundedElastic())
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
                .subscribeOn(Schedulers.boundedElastic())
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

    /** 单页上限。报文行带正文预览与三个跨表子查询，比普通列表贵得多，上限压得比常规更狠。 */
    private static final long MAX_PAGE_SIZE = 200;

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
                    "正文关键字检索必须同时指定以下之一：接入系统、模型、TraceID（至少 %d 位前缀）、"
                            + "或两端齐全且跨度不超过 %d 小时的时间范围。"
                            + "（关键字是 LONGTEXT 上的模糊匹配，走不了索引，不收窄会全表扫描拖垮数据库）",
                    PayloadQueryBO.tracePrefixMin(), PayloadQueryBO.timeSpanMaxHours()));
        }

        if (q.getPageSize() > MAX_PAGE_SIZE) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "pageSize 不能超过 " + MAX_PAGE_SIZE + "，当前传入 " + q.getPageSize()
                            + "（报文行含正文预览与跨表 Token 查询，单页过大会长时间占用数据库连接）");
        }
        if (q.getPageSize() <= 0 || q.getPageIndex() <= 0) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "pageIndex 与 pageSize 必须为正数");
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
        // 内容来筛选永远查不到。likeRight 在 idx_trace_id 上是范围扫描，索引仍然生效。
        if (notBlank(traceId)) {
            w.likeRight(RequestPayloadEntity::getTraceId, escapeLike(traceId.trim()));
        }

        if (notBlank(keyword)) {
            // 请求与响应都匹配：排查既可能从"用户问了什么"入手，也可能从"模型答了什么"入手。
            // 只搜 response_text 不搜 response_raw —— raw 是 SSE 分帧原文，同一句话被切碎在多帧里，
            // LIKE 匹配不到完整词，搜它只会给出误导性的空结果。
            String kw = escapeLike(keyword.trim());
            w.and(x -> x.like(RequestPayloadEntity::getRequestBody,  kw)
                        .or()
                        .like(RequestPayloadEntity::getResponseText, kw));
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
     * 转义 LIKE 的通配符。
     *
     * <p>MyBatis-Plus 的 {@code like()} 只负责把值包成 {@code %值%}，<b>不转义</b>值里的
     * {@code %} 和 {@code _}。不处理的话，关键字填一个 {@code %} 就变成 {@code LIKE '%%%'}
     * ——匹配全部行，把"必须先收窄"的防护整个绕过去，等于一次点击换一轮全表扫 LONGTEXT。
     * 这不是 SQL 注入（值仍是绑定参数），是通配符被当成语法解释。
     *
     * <p>用反斜杠转义，不加 {@code ESCAPE} 子句：MySQL 的 LIKE 默认转义符就是反斜杠。
     */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }
}
