package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pab.ficc.ibp.domain.base.vo.PageVo;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AuditQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLogEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AuditLogVO;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 审计日志查询接口
 * GET /modelgate/audit — 原始审计明细，分页返回，按 create_time + id 倒序
 */
@Tag(name = "审计日志", description = "查询 API 调用明细，支持按系统、模型、状态码、traceId 筛选")
@RestController
@RequestMapping(UrlConstant.MG_AUDIT)
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;

    @Operation(summary = "分页查询审计日志（pageIndex 从 1 开始，pageSize 上限 200；按时间倒序）")
    @GetMapping
    public Mono<Result<PageVo<AuditLogVO>>> query(AuditQueryBO query) {
        return Mono.fromCallable(() -> PageVo.of(doQuery(query), AuditLogVO::of))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    /**
     * 按条件分页查审计明细。
     *
     * <p>排序用 {@code create_time DESC, id DESC}：审计是高频追加表，同一毫秒内可能写入多条，
     * 只按 create_time 排在翻页时会出现重复行/漏行——**必须**补一个唯一列做次级排序。
     *
     * <p>原实现是 {@code LIMIT 200} 硬截断，超过 200 条的历史记录根本查不到；改分页后可翻到全量。
     *
     * <p>分页条件由前端经 {@link AuditQueryBO}（继承行内 {@code BaseBo}）传入。
     */
    private IPage<AuditLogEntity> doQuery(AuditQueryBO query) {
        AuditQueryBO q = query != null ? query : new AuditQueryBO();
        String appId   = q.getAppId();
        String modelId = q.getModelId();
        Integer status = q.getStatus();
        String traceId = q.getTraceId();

        return auditLogMapper.selectPage(new Page<>(q.getPageIndex(), q.getPageSize()),
                new LambdaQueryWrapper<AuditLogEntity>()
                        .eq(appId   != null && !appId.isBlank(),   AuditLogEntity::getAppId,      appId)
                        .eq(modelId != null && !modelId.isBlank(), AuditLogEntity::getModelId,    modelId)
                        .eq(status  != null,                       AuditLogEntity::getHttpStatus, status)
                        .eq(traceId != null && !traceId.isBlank(), AuditLogEntity::getTraceId,    traceId)
                        .orderByDesc(AuditLogEntity::getCreateTime)
                        .orderByDesc(AuditLogEntity::getId));
    }
}
