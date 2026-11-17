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
import com.pab.ficc.ibp.modelgate.server.domain.vo.PersonalAccessViews;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import com.pab.ficc.ibp.modelgate.server.service.AppScopeResolver;
import com.pab.ficc.ibp.modelgate.server.service.AuditQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.List;

/**
 * 审计日志查询接口
 * GET /audit — 原始审计明细，分页返回，按 create_time + id 倒序
 */
@Tag(name = "审计日志", description = "查询 API 调用明细，支持按 appId、应用编码、模型、状态码、traceId 筛选")
@RestController
@RequestMapping(UrlConstant.MG_AUDIT)
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;
    private final AppScopeResolver appScopeResolver;
    private final AuditQueryService auditQueryService;

    @Operation(summary = "分页查询审计日志（pageIndex 从 1 开始，pageSize 上限 200；按时间倒序）")
    @GetMapping(UrlConstant.QUERY)
    public Mono<Result<PageVo<AuditLogVO>>> query(AuditQueryBO query) {
        // mg_audit_log 保留 90 天、是全库增长最快的表，这里的护栏最要紧：
        // 实测未加护栏时 pageSize=100000 会返回整张表（HTTP 200 / 2.8s / 1.7MB）
        PageSupport.validate(query, "审计日志");
        return Mono.fromCallable(() -> {
                    IPage<AuditLogEntity> page = auditQueryService.applicationFilters(query)
                            .map(filters -> selectPage(query, filters))
                            .orElseGet(() -> new Page<>(query.getPageIndex(), query.getPageSize()));
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

    @GetMapping("/personal/users")
    public Mono<Result<List<PersonalAccessViews.UserOption>>> personalUsers(@RequestParam("keyword") String keyword) {
        return Mono.fromCallable(() -> appScopeResolver.findPersonalUsers(keyword))
                .subscribeOn(AdminScheduler.get()).timeout(AdminScheduler.REQUEST_TIMEOUT).map(Result::success);
    }

    @GetMapping("/personal/query")
    public Mono<Result<PageVo<AuditLogVO>>> personalQuery(@RequestParam("um") String um, AuditQueryBO query) {
        PageSupport.validate(query, "个人审计日志");
        return Mono.fromCallable(() -> {
                    var user = appScopeResolver.requirePersonalByUm(um);
                    var page = selectPage(query, auditQueryService.personalFilters(query, user.getAppId()));
                    return PageVo.of(page, e -> {
                        AuditLogVO vo = AuditLogVO.of(e);
                        vo.setAppId(null);
                        vo.setUm(user.getPersonUm());
                        vo.setPersonName(user.getAppName());
                        return vo;
                    });
                })
                .subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT)
                .map(Result::success);
    }

    private IPage<AuditLogEntity> selectPage(AuditQueryBO query, LambdaQueryWrapper<AuditLogEntity> filters) {
        return auditLogMapper.selectPage(new Page<>(query.getPageIndex(), query.getPageSize()),
                filters.orderByDesc(AuditLogEntity::getCreateTime).orderByDesc(AuditLogEntity::getId));
    }
}
