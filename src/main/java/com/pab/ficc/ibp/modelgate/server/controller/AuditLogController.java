package com.pab.ficc.ibp.modelgate.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLog;
import com.pab.ficc.ibp.modelgate.server.domain.vo.AuditLogVO;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 审计日志查询接口
 * GET /admin/audit — 原始审计明细，最多返回 200 条，按 created_at 倒序
 */
@Tag(name = "审计日志", description = "查询 API 调用明细，支持按系统、模型、状态码、traceId 筛选")
@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;

    @Operation(summary = "查询审计日志（最多返回 200 条，按时间倒序）")
    @GetMapping
    public Mono<Result<List<AuditLogVO>>> query(
            @Parameter(description = "系统 ID，不传则不过滤") @RequestParam(required = false) String appId,
            @Parameter(description = "模型 ID，不传则不过滤") @RequestParam(required = false) String modelId,
            @Parameter(description = "HTTP 状态码，不传则不过滤") @RequestParam(required = false) Integer status,
            @Parameter(description = "链路追踪 ID") @RequestParam(required = false) String traceId) {

        return Mono.fromCallable(() -> doQuery(appId, modelId, status, traceId)
                        .stream().map(AuditLogVO::of).collect(Collectors.toList()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    private List<AuditLog> doQuery(String appId, String modelId, Integer status, String traceId) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(appId   != null && !appId.isBlank(),   AuditLog::getAppId,      appId)
                .eq(modelId != null && !modelId.isBlank(), AuditLog::getModelId,    modelId)
                .eq(status  != null,                       AuditLog::getHttpStatus, status)
                .eq(traceId != null && !traceId.isBlank(), AuditLog::getTraceId,    traceId)
                .orderByDesc(AuditLog::getCreatedAt)
                .last("LIMIT 200");

        return auditLogMapper.selectList(wrapper);
    }
}
