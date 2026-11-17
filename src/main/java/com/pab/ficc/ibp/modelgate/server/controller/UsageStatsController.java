package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.domain.bo.AuditQueryBO;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLogEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import com.pab.ficc.ibp.modelgate.server.service.AuditQueryService;
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
import java.time.LocalTime;
import java.util.HashMap;
import java.util.Map;

@Tag(name = "用量统计", description = "查询 API 调用量、Token 消耗、平均延迟、成功率汇总数据")
@RestController
@RequestMapping(UrlConstant.MG_STATS)
@RequiredArgsConstructor
public class UsageStatsController {

    private final AuditLogMapper auditLogMapper;
    private final AuditQueryService auditQueryService;

    @Operation(summary = "用量统计汇总（总请求数 / Token 消耗 / 平均延迟 / 成功率）")
    @GetMapping(UrlConstant.USAGE)
    public Mono<Result<Map<String, Object>>> usage(
            AuditQueryBO query,
            @Parameter(description = "开始日期（yyyy-MM-dd），默认近 30 天") @RequestParam(name = "start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @Parameter(description = "结束日期（yyyy-MM-dd），默认今天") @RequestParam(name = "end", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        setDefaultDates(query, start, end);
        return Mono.fromCallable(() -> auditQueryService.applicationFilters(query)
                        .map(filters -> {
                            if (query.getTrafficType() == null || query.getTrafficType().isBlank()) {
                                filters.eq(AuditLogEntity::getTrafficType, "model");
                            }
                            return buildStats(auditLogMapper.selectFilteredUsageStats(filters));
                        }).orElseGet(this::zeroStats))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    @GetMapping("/personal/usage")
    public Mono<Result<Map<String, Object>>> personalUsage(@RequestParam(value = "um", required = false) String um,
                                                           AuditQueryBO query) {
        setDefaultDates(query, null, null);
        return Mono.fromCallable(() -> auditQueryService.personalFilters(query, um)
                        .map(filters -> buildStats(auditLogMapper.selectFilteredUsageStats(filters)))
                        .orElseGet(this::zeroStats))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    private static void setDefaultDates(AuditQueryBO query, LocalDate start, LocalDate end) {
        LocalDate today = LocalDate.now();
        if (query.getStartTime() == null) query.setStartTime((start != null ? start : today.minusDays(30)).atStartOfDay());
        if (query.getEndTime() == null) query.setEndTime((end != null ? end : today).atTime(LocalTime.MAX));
    }

    private Map<String, Object> buildStats(Map<String, Object> row) {
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

    /** 成功率给 1.0 而非 0：0 次请求时"成功率 0%"会被误读成全部失败。与上面分母为 0 的处理一致。 */
    private Map<String, Object> zeroStats() {
        Map<String, Object> result = new HashMap<>();
        result.put("totalRequests", 0L);
        result.put("totalInputTokens", 0L);
        result.put("totalOutputTokens", 0L);
        result.put("avgLatencyMs", 0.0);
        result.put("successRate", 1.0);
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
