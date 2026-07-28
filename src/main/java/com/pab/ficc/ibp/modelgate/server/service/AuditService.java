package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLogEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * 审计日志服务。
 *
 * <p>每条审计记录先放入内存 {@link LinkedBlockingQueue}，
 * 由定时任务每 2 秒批量写入 MySQL，单批最多 5000 条。
 * 相比每请求一次 INSERT，在 30 QPS 下减少约 99.7% 的写操作次数。
 *
 * <p><b>降级策略：</b>
 * <ul>
 *   <li>队列满时（默认容量 200,000）：丢弃新条目并打 WARN 日志，不阻塞主链路（fail-open）。</li>
 *   <li>批量 INSERT 失败：仅打 WARN，本批数据丢失，下一批继续（不重试，避免积压）。</li>
 * </ul>
 *
 * <p><b>开关：</b>
 * <ul>
 *   <li>{@code modelgate.audit.enabled=false}：完全关闭审计。</li>
 *   <li>{@code modelgate.audit.db-write=false}：跳过 DB 写入（DB 故障降级，月度 Token 累计仍正常）。</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogMapper auditLogMapper;
    private final RateLimitService rateLimitService;
    private final MeterRegistry meterRegistry;

    /** 因队列满被丢弃的审计条数（压测/生产监控用） */
    private Counter droppedCounter;

    @Value("${modelgate.audit.enabled:true}")
    private boolean auditEnabled;

    @Value("${modelgate.audit.db-write:true}")
    private boolean dbWriteEnabled;

    /** 内存队列容量上限，超出后新条目被丢弃（fail-open）而非阻塞请求线程 */
    @Value("${modelgate.audit.buffer-size:200000}")
    private int bufferSize;

    private LinkedBlockingQueue<AuditLogEntity> queue;

    @PostConstruct
    public void init() {
        queue = new LinkedBlockingQueue<>(bufferSize);

        // 队列深度（当前积压条数）——压测时观测是否逼近 bufferSize，逼近即开始丢日志
        Gauge.builder("modelgate.audit.queue.size", queue, LinkedBlockingQueue::size)
                .description("Current backlog of audit log entries waiting to be flushed")
                .register(meterRegistry);
        // 队列容量上限，便于面板计算水位百分比
        Gauge.builder("modelgate.audit.queue.capacity", this, s -> s.bufferSize)
                .description("Configured audit queue capacity")
                .register(meterRegistry);
        // 因队列满而丢弃的累计条数
        droppedCounter = Counter.builder("modelgate.audit.dropped.total")
                .description("Total audit log entries dropped because the queue was full")
                .register(meterRegistry);
    }

    /**
     * 记录一次 API 调用的审计信息。由 {@link com.pab.ficc.ibp.modelgate.server.filter.TraceFilter}
     * 在响应完成后的 {@code doFinally} 钩子中调用，不阻塞请求处理链路。
     *
     * @param inputTokens  本次调用消耗的 input token 数（暂时为 0，待从响应体解析后更新）
     * @param outputTokens 本次调用消耗的 output token 数（同上）
     */
    public void log(String appId, String modelId, String credType, String clientIp,
                    String requestPath, int httpStatus, long latencyMs,
                    int inputTokens, int outputTokens, String errorMsg, String traceId) {
        if (!auditEnabled) return;

        AuditLogEntity entry = buildEntry(traceId, appId, modelId, credType, clientIp,
                requestPath, httpStatus, latencyMs, inputTokens, outputTokens, errorMsg);

        // 无论 dbWriteEnabled 是否开启，都必须入队：
        // flush() 依赖队列内容来累计月度 Token 计数；若 dbWriteEnabled=false 时跳过入队，
        // Redis 月度计数器永远不更新，导致月度配额彻底失效。
        boolean offered = queue.offer(entry);
        if (!offered) {
            droppedCounter.increment();
            log.warn("[AuditService] Queue full, dropping audit log traceId={}", traceId);
        }
    }

    /**
     * 定时批量刷盘：每 2 秒从内存队列取最多 5000 条，一次 batchInsert 写入 DB。
     *
     * <p>同时触发月度 Token 累计：将本批次消耗的 token 按 app+model 分组汇总后
     * 写入 Redis 月度计数器，供 QuotaFilter 的月 Token 配额检查使用。
     * 分组汇总可以将 N 次 Redis 调用压缩为最多 M（模型数）次，大幅减少 Redis 压力。
     */
    @Scheduled(fixedDelayString = "${modelgate.audit.flush-interval-ms:2000}")
    public void flush() {
        if (queue == null || queue.isEmpty()) return;
        List<AuditLogEntity> batch = new ArrayList<>(5000);
        queue.drainTo(batch, 5000);
        if (batch.isEmpty()) return;

        // DB 写入：dbWriteEnabled=false 时跳过（降级），但月度 Token 累计必须始终执行
        if (dbWriteEnabled) {
            try {
                auditLogMapper.batchInsert(batch);
            } catch (Exception e) {
                log.warn("[AuditService] Batch insert failed size={}: {}", batch.size(), e.getMessage());
            }
        }

        // 月度 Token 计数无论 DB 是否可用都要更新，否则 QuotaFilter 的月度配额会失效
        accumulateMonthlyTokens(batch);
    }

    /**
     * 将本批次的 token 消耗按 (appId, modelId) 分组汇总，批量写入 Redis 月度计数器。
     *
     * <p>不在每条审计记录入队时单独累加，原因：
     * <ol>
     *   <li>每次请求都写 Redis 会带来大量 EVAL 操作；</li>
     *   <li>批量汇总后，同一 app+model 的多次请求只需一次 Redis INCRBY，I/O 显著减少。</li>
     * </ol>
     */
    private void accumulateMonthlyTokens(List<AuditLogEntity> batch) {
        // 使用 record 作为 Map key，按 app+model 分组累加 token 数
        record Key(String appId, String modelId) {}
        Map<Key, Long> totals = new HashMap<>();
        for (AuditLogEntity entry : batch) {
            if (entry.getAppId() == null || entry.getModelId() == null) continue;
            long tokens = (entry.getInputTokens() != null ? entry.getInputTokens() : 0)
                    + (entry.getOutputTokens() != null ? entry.getOutputTokens() : 0);
            if (tokens <= 0) continue;
            totals.merge(new Key(entry.getAppId(), entry.getModelId()), tokens, Long::sum);
        }
        totals.forEach((k, tokens) -> rateLimitService.addMonthlyTokens(k.appId(), k.modelId(), tokens));
    }

    private AuditLogEntity buildEntry(String traceId, String appId, String modelId, String credType,
                                 String clientIp, String requestPath, int httpStatus,
                                 long latencyMs, int inputTokens, int outputTokens, String errorMsg) {
        AuditLogEntity entry = new AuditLogEntity();
        entry.setTraceId(traceId);
        entry.setAppId(appId);
        entry.setModelId(modelId);
        entry.setCredType(credType);
        entry.setClientIp(clientIp);
        entry.setRequestPath(requestPath);
        entry.setHttpStatus(httpStatus);
        entry.setLatencyMs((int) latencyMs);
        entry.setInputTokens(inputTokens);
        entry.setOutputTokens(outputTokens);
        entry.setErrorMsg(errorMsg);
        entry.setCreateTime(LocalDateTime.now());
        return entry;
    }

}
