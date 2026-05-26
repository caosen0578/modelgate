package com.pab.ficc.ibp.modelgate.server.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLog;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;

/**
 * 审计日志查询接口
 * GET /admin/audit — 原始审计明细，最多返回 200 条，按 created_at 倒序
 */
@RestController
@RequestMapping("/admin/audit")
@RequiredArgsConstructor
public class AuditLogController {

    private final AuditLogMapper auditLogMapper;

    @GetMapping
    public Mono<Result<List<AuditLog>>> query(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String traceId) {

        return Mono.fromCallable(() -> doQuery(appId, modelId, status, traceId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
    }

    private List<AuditLog> doQuery(String appId, String modelId, Integer status, String traceId) {
        LambdaQueryWrapper<AuditLog> wrapper = new LambdaQueryWrapper<AuditLog>()
                .eq(appId   != null && !appId.isBlank(),   AuditLog::getAppId,     appId)
                .eq(modelId != null && !modelId.isBlank(), AuditLog::getModelId,   modelId)
                .eq(status  != null,                       AuditLog::getHttpStatus, status)
                .eq(traceId != null && !traceId.isBlank(), AuditLog::getTraceId,   traceId)
                .orderByDesc(AuditLog::getCreatedAt)
                .last("LIMIT 200");

        return auditLogMapper.selectList(wrapper);
    }
}
