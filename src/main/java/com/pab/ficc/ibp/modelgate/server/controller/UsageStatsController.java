package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.common.UrlConstant;

import com.pab.ficc.cus.web.result.Result;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

@Tag(name = "用量统计", description = "查询 API 调用量、Token 消耗、平均延迟、成功率汇总数据")
@RestController
@RequestMapping(UrlConstant.ADMIN_STATS)
@RequiredArgsConstructor
public class UsageStatsController {

    private final AuditLogMapper auditLogMapper;

    @Operation(summary = "用量统计汇总（总请求数 / Token 消耗 / 平均延迟 / 成功率）")
    @GetMapping(UrlConstant.USAGE)
    public Mono<Result<Map<String, Object>>> usage(
            @Parameter(description = "系统 ID，不传则汇总全部") @RequestParam(required = false) String appId,
            @Parameter(description = "模型 ID，不传则汇总全部") @RequestParam(required = false) String modelId,
            @Parameter(description = "开始日期（yyyy-MM-dd），默认近 30 天") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @Parameter(description = "结束日期（yyyy-MM-dd），默认今天") @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        LocalDateTime startDt = start != null ? start.atStartOfDay() : LocalDate.now().minusDays(30).atStartOfDay();
        LocalDateTime endDt = end != null ? end.atTime(LocalTime.MAX) : LocalDate.now().atTime(LocalTime.MAX);

        return Mono.fromCallable(() -> buildStats(appId, modelId, startDt, endDt))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    private Map<String, Object> buildStats(String appId, String modelId,
                                            LocalDateTime start, LocalDateTime end) {
        Map<String, Object> row = auditLogMapper.selectUsageStats(appId, modelId, start, end);

        long totalRequests = toLong(row.get("totalRequests"));
        long successCount = toLong(row.get("successCount"));

        Map<String, Object> result = new HashMap<>();
        result.put("totalRequests", totalRequests);
        result.put("totalInputTokens", toLong(row.get("totalInputTokens")));
        result.put("totalOutputTokens", toLong(row.get("totalOutputTokens")));
        result.put("avgLatencyMs", toDouble(row.get("avgLatencyMs")));
        result.put("successRate", totalRequests > 0 ? (double) successCount / totalRequests : 1.0);
        return result;
    }

    private long toLong(Object val) {
        if (val == null) return 0L;
        return ((Number) val).longValue();
    }

    private double toDouble(Object val) {
        if (val == null) return 0.0;
        return ((Number) val).doubleValue();
    }
}
