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
                    RequestPayloadEntity e = payloadMapper.selectById(id);
                    if (e == null) {
                        // 报文保留期只有几天，查不到多半是已被清理而非 id 写错，提示要说清楚
                        throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                                "报文不存在或已过保留期被清理: id=" + id);
                    }
                    return PayloadDetailVO.of(e);
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

    /**
     * 关键字检索的前置校验。
     *
     * <p>正文检索是 LONGTEXT 上的 {@code LIKE '%x%'}，无法走索引。若允许它单独使用，
     * 一次点击就是一轮全表扫描 + 把全部正文读进 buffer pool，足以把在线业务的 DB 拖垮。
     * 强制搭配一个能走索引的条件（系统/模型/traceId/时间范围），让 MySQL 先收窄候选集。
     */
    private void validate(PayloadQueryBO q) {
        String keyword = q.getKeyword();
        if (keyword != null && !keyword.isBlank() && !q.hasNarrowingFilter()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER,
                    "正文关键字检索必须同时指定 接入系统 / 模型 / TraceID / 时间范围 中的至少一项"
                            + "（全表扫描 LONGTEXT 会拖垮数据库）");
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
                .eq(notBlank(traceId),      RequestPayloadEntity::getTraceId,        traceId)
                .eq(notBlank(finishReason), RequestPayloadEntity::getFinishReason,   finishReason)
                .eq(notBlank(truncated),    RequestPayloadEntity::getTruncated,      truncated)
                .eq(q.getIsStream() != null,       RequestPayloadEntity::getIsStream,       q.getIsStream())
                .eq(q.getUpstreamStatus() != null, RequestPayloadEntity::getUpstreamStatus, q.getUpstreamStatus())
                .ge(q.getStartTime() != null,      RequestPayloadEntity::getCreateTime,     q.getStartTime())
                .le(q.getEndTime() != null,        RequestPayloadEntity::getCreateTime,     q.getEndTime());

        if (notBlank(keyword)) {
            // 请求与响应都匹配：排查既可能从"用户问了什么"入手，也可能从"模型答了什么"入手。
            // 只搜 response_text 不搜 response_raw —— raw 是 SSE 分帧原文，同一句话被切碎在多帧里，
            // LIKE 匹配不到完整词，搜它只会给出误导性的空结果。
            w.and(x -> x.like(RequestPayloadEntity::getRequestBody,  keyword)
                        .or()
                        .like(RequestPayloadEntity::getResponseText, keyword));
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
}
