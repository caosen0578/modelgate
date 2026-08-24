package com.pab.ficc.ibp.modelgate.server.controller;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;

import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import com.pab.ficc.ibp.modelgate.server.service.AppScopeResolver;
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
import java.util.List;
import java.util.Map;

@Tag(name = "用量统计", description = "查询 API 调用量、Token 消耗、平均延迟、成功率汇总数据")
@RestController
@RequestMapping(UrlConstant.MG_STATS)
@RequiredArgsConstructor
public class UsageStatsController {

    private final AuditLogMapper auditLogMapper;
    private final AppScopeResolver appScopeResolver;

    @Operation(summary = "用量统计汇总（总请求数 / Token 消耗 / 平均延迟 / 成功率）")
    @GetMapping(UrlConstant.USAGE)
    public Mono<Result<Map<String, Object>>> usage(
            @Parameter(description = "系统 ID，不传则汇总全部") @RequestParam(name = "appId", required = false) String appId,
            @Parameter(description = "应用编码（mg_app.app_code，精确匹配），不传则不按应用编码过滤")
            @RequestParam(name = "appCode", required = false) String appCode,
            @Parameter(description = "模型 ID，不传则汇总全部") @RequestParam(name = "modelId", required = false) String modelId,
            @Parameter(description = "开始日期（yyyy-MM-dd），默认近 30 天") @RequestParam(name = "start", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate start,
            @Parameter(description = "结束日期（yyyy-MM-dd），默认今天") @RequestParam(name = "end", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate end) {

        LocalDateTime startDt = start != null ? start.atStartOfDay() : LocalDate.now().minusDays(30).atStartOfDay();
        LocalDateTime endDt = end != null ? end.atTime(LocalTime.MAX) : LocalDate.now().atTime(LocalTime.MAX);

        return Mono.fromCallable(() -> buildStats(appId, appCode, modelId, startDt, endDt))
                .subscribeOn(Schedulers.boundedElastic())
                .map(Result::success);
    }

    /**
     * <p><b>必须与审计列表同口径</b>：审计页顶部这四个卡片和下方列表用的是同一组筛选条件，
     * 卡片少支持一个条件，用户就会拿着"全量汇总"去解释"按编码筛出来的列表"。
     * 所以这里和 {@code AuditLogController} 走的是同一个 {@link AppScopeResolver}。
     */
    private Map<String, Object> buildStats(String appId, String appCode, String modelId,
                                            LocalDateTime start, LocalDateTime end) {
        AppScopeResolver.AppScope scope = appScopeResolver.resolve(appId, appCode);
        if (scope.matchNothing()) {
            // 编码查无应用 / appId 与编码矛盾：直接给全零，不查库。
            // 与列表侧"返回空页"保持一致，避免卡片有数、列表为空这种自相矛盾的展示。
            return zeroStats();
        }
        List<String> appIds = scope.eqAppId() != null ? List.of(scope.eqAppId()) : scope.inAppIds();
        Map<String, Object> row = auditLogMapper.selectUsageStats(appIds, modelId, start, end);

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
