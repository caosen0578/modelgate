package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.Result;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
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

@RestController
@RequestMapping("/admin/stats")
@RequiredArgsConstructor
public class UsageStatsController {

    private final AuditLogMapper auditLogMapper;

    @GetMapping("/usage")
    public Mono<Result<Map<String, Object>>> usage(
            @RequestParam(required = false) String appId,
            @RequestParam(required = false) String modelId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        LocalDateTime startDt = start != null ? start.atStartOfDay() : LocalDate.now().minusDays(30).atStartOfDay();
        LocalDateTime endDt = end != null ? end.atTime(LocalTime.MAX) : LocalDate.now().atTime(LocalTime.MAX);

        return Mono.fromCallable(() -> buildStats(appId, modelId, startDt, endDt))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::ok);
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
