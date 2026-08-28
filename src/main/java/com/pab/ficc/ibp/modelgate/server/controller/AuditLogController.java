package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.pab.ficc.ibp.domain.base.vo.PageVo;
import com.pab.ficc.ibp.modelgate.server.common.AdminScheduler;
import com.pab.ficc.ibp.modelgate.server.common.PageSupport;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AuditQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLogEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AuditLogVO;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import com.pab.ficc.ibp.modelgate.server.service.AppScopeResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 审计日志查询接口
 * GET /modelgate/audit — 原始审计明细，分页返回，按 create_time + id 倒序
 */
@Tag(name = "审计日志", description = "查询 API 调用明细，支持按 appId、应用编码、模型、状态码、traceId 筛选")
@RestController
@RequestMapping(UrlConstant.MG_AUDIT)
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;
    private final AppScopeResolver appScopeResolver;

    @Operation(summary = "分页查询审计日志（pageIndex 从 1 开始，pageSize 上限 200；按时间倒序）")
    @GetMapping
    public Mono<Result<PageVo<AuditLogVO>>> query(AuditQueryBO query) {
        // mg_audit_log 保留 90 天、是全库增长最快的表，这里的护栏最要紧：
        // 实测未加护栏时 pageSize=100000 会返回整张表（HTTP 200 / 2.8s / 1.7MB）
        PageSupport.validate(query, "审计日志");
        return Mono.fromCallable(() -> {
                    IPage<AuditLogEntity> page = doQuery(query);
                    // 应用编码不在审计表里，按当前页 appId 一次性批量反查后回填（不是 N+1）。
                    // 查不到（应用已删除 / 鉴权失败行 appId 为空）时留 null，页面显示 "-"。
                    Map<String, String> codes = appScopeResolver.appCodeByAppId(
                            page.getRecords().stream().map(AuditLogEntity::getAppId).toList());
                    return PageVo.of(page, e -> {
                        AuditLogVO vo = AuditLogVO.of(e);
                        vo.setAppCode(codes.get(e.getAppId()));
                        return vo;
                    });
                })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
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
        String modelId = q.getModelId();
        Integer status = q.getStatus();
        String traceId = q.getTraceId();
        String trafficType = q.getTrafficType();
        String serviceId = q.getServiceId();

        // appId + 应用编码 → 审计表上可执行的条件。审计表没有 app_code 列，
        // 由 AppScopeResolver 先在小表 mg_app 解析，理由见该类注释。
        AppScopeResolver.AppScope scope = appScopeResolver.resolve(q.getAppId(), q.getAppCode());
        if (scope.matchNothing()) {
            // 条件注定命中不了任何行（编码查无此应用 / appId 与编码矛盾）。
            // ⚠️ 必须直接返回空页：漏掉这一分支就等于"不加 app 过滤"，
            // 会把全部系统的审计当成该应用编码的审计返回，是最误导人的一种错。
            return new Page<>(q.getPageIndex(), q.getPageSize());
        }

        return auditLogMapper.selectPage(new Page<>(q.getPageIndex(), q.getPageSize()),
                new LambdaQueryWrapper<AuditLogEntity>()
                        .eq(scope.eqAppId() != null,               AuditLogEntity::getAppId,      scope.eqAppId())
                        .in(scope.inAppIds() != null,              AuditLogEntity::getAppId,      scope.inAppIds())
                        .eq(modelId != null && !modelId.isBlank(), AuditLogEntity::getModelId,    modelId)
                        .eq(status  != null,                       AuditLogEntity::getHttpStatus, status)
                        .eq(traceId != null && !traceId.isBlank(), AuditLogEntity::getTraceId,    traceId)
                        // 流量类型与服务编码：都走等值匹配，为空则不过滤（两类流量都返回）
                        .eq(trafficType != null && !trafficType.isBlank(),
                                AuditLogEntity::getTrafficType, trafficType)
                        .eq(serviceId != null && !serviceId.isBlank(),
                                AuditLogEntity::getServiceId,   serviceId)
                        .orderByDesc(AuditLogEntity::getCreateTime)
                        .orderByDesc(AuditLogEntity::getId));
    }
}
