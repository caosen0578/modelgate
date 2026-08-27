package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLogEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
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
 *   <li>批量 INSERT 失败：<b>整批进入 {@link PendingWriteBuffer} 等待重试</b>，
 *       带指数退避与毒药记录隔离；只有"拆到单条仍写不进去"或"重试缓冲区超上限"
 *       才会真正丢弃，且一律打 ERROR。</li>
 * </ul>
 *
 * <p><b>为什么必须重试</b>：原实现是 {@code drainTo} 先把数据取出队列、写失败只打一行 WARN，
 * 数据已离开队列就<b>永久消失</b>。管理面一个慢查询占满 Druid 连接池，
 * 就足以让 5000 条审计记录当场蒸发，月底对账对不上且无从补回。
 * 审计表是计费与合规依据，不能按"尽力而为"对待。
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

    /**
     * 因队列满被丢弃的累计条数。
     *
     * <p>原为 Micrometer Counter（{@code modelgate.audit.dropped.total}），
     * Prometheus 出口移除后改为进程内累计值，随每条丢弃 WARN 一起打出——
     * 丢审计是必须能察觉的事，不能因为没了指标出口就变成完全不可见。
     */
    private final java.util.concurrent.atomic.AtomicLong droppedTotal = new java.util.concurrent.atomic.AtomicLong();

    @Value("${modelgate.audit.enabled:true}")
    private boolean auditEnabled;

    @Value("${modelgate.audit.db-write:true}")
    private boolean dbWriteEnabled;

    /** 内存队列容量上限，超出后新条目被丢弃（fail-open）而非阻塞请求线程 */
    @Value("${modelgate.audit.buffer-size:200000}")
    private int bufferSize;

    /**
     * 优雅退出时刷盘的最长耗时。
     *
     * <p>取 15 秒：足够把积压写完，又不会长到让容器平台等不及改用强杀
     * （强杀会连正在处理的 LLM 请求一起断掉，比丢几条审计严重）。
     */
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MS = 15_000;

    private LinkedBlockingQueue<AuditLogEntity> queue;

    /**
     * 写库失败后的重试缓冲区。容量与主队列一致：数据库不可用时，
     * 最坏情况相当于把主队列的内容整体搬到这里，堆占用是可预期的。
     */
    private PendingWriteBuffer<AuditLogEntity> retryBuffer;

    @PostConstruct
    public void init() {
        queue = new LinkedBlockingQueue<>(bufferSize);
        retryBuffer = new PendingWriteBuffer<>("AuditService", bufferSize,
                e -> String.format("traceId=%s appId=%s modelId=%s status=%s time=%s",
                        e.getTraceId(), e.getAppId(), e.getModelId(), e.getHttpStatus(), e.getCreateTime()));
        log.info("[AuditService] 审计队列就绪, capacity={}", bufferSize);
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
                    int inputTokens, int outputTokens, String tokenSource,
                    String errorMsg, String traceId) {
        log(appId, modelId, credType, clientIp, requestPath, httpStatus, latencyMs,
                inputTokens, outputTokens, tokenSource, errorMsg, traceId, "model", null);
    }

    /**
     * 带流量类型的重载。服务流量传 {@code ("service", serviceId)}，模型流量走上面那个重载。
     *
     * <p>保留旧签名而不是直接改参数：审计是全链路唯一的"发生过什么"的记录，
     * 改签名会让所有调用点一起变动，漏改一处的后果是那类请求彻底没有审计——
     * 而这种缺失只有在事后排查时才会发现。
     */
    public void log(String appId, String modelId, String credType, String clientIp,
                    String requestPath, int httpStatus, long latencyMs,
                    int inputTokens, int outputTokens, String tokenSource,
                    String errorMsg, String traceId, String trafficType, String serviceId) {
        if (!auditEnabled) return;

        AuditLogEntity entry = buildEntry(traceId, appId, modelId, credType, clientIp,
                requestPath, httpStatus, latencyMs, inputTokens, outputTokens, tokenSource, errorMsg);
        entry.setTrafficType(trafficType == null ? "model" : trafficType);
        entry.setServiceId(serviceId);

        // 无论 dbWriteEnabled 是否开启，都必须入队：
        // flush() 依赖队列内容来累计月度 Token 计数；若 dbWriteEnabled=false 时跳过入队，
        // Redis 月度计数器永远不更新，导致月度配额彻底失效。
        boolean offered = queue.offer(entry);
        if (!offered) {
            log.warn("[AuditService] Queue full, dropping audit log traceId={}, 累计丢弃={}",
                    traceId, droppedTotal.incrementAndGet());
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
        if (queue == null) return;

        // 先重试积压：数据库恢复后新数据源源不断，不优先处理积压的话旧数据永远排不上队。
        // 重试路径**只做 DB 写入**，绝不再碰月度 Token——见下方 accumulateMonthlyTokens 的说明。
        if (dbWriteEnabled) {
            retryBuffer.retryDue(auditLogMapper::batchInsert);
        }

        if (queue.isEmpty()) return;
        List<AuditLogEntity> batch = new ArrayList<>(5000);
        queue.drainTo(batch, 5000);
        if (batch.isEmpty()) return;

        // 月度 Token 计数必须在这里、且**只在这里**累加一次：
        //   · 无论 DB 是否可用都要更新，否则 QuotaFilter 的月度配额会失效；
        //   · 放在 DB 写入之前，保证"写库失败进重试队列"这条路径不会漏加；
        //   · 重试时**不再累加**——Redis 的 INCRBY 不是幂等的，重试一次就多算一次 token，
        //     数据库抖动几轮就能把用户的月度配额凭空吃光，直接误伤线上业务。
        accumulateMonthlyTokens(batch);

        // DB 写入：dbWriteEnabled=false 时跳过（降级）
        if (!dbWriteEnabled) return;
        try {
            auditLogMapper.batchInsert(batch);
        } catch (Exception e) {
            // 不再直接丢弃：整批交给重试缓冲区，由后续 flush 按退避重试
            log.warn("[AuditService] 批量落库失败，转入重试队列: {} 条, 原因={}", batch.size(), e.getMessage());
            retryBuffer.offerFailed(batch);
        }
    }

    /**
     * 优雅退出时把内存里未落库的数据尽力写完。
     *
     * <p>由容器平台的优雅退出端点调用，也由 {@link #onShutdown()} 兜底。
     * 内存队列与重试缓冲区里的数据一旦随进程退出就永久消失，
     * 而这正是"每次发版都悄悄丢一批审计"的来源——发版是最频繁的进程退出场景。
     *
     * <p>有<b>时限</b>：数据库不可用时不能无限期卡住退出流程，
     * 否则平台等不及会强杀，反而比有序退出丢得更多。
     */
    public void drainForShutdown() {
        if (queue == null) return;
        long deadline = System.currentTimeMillis() + SHUTDOWN_DRAIN_TIMEOUT_MS;
        int drained = 0;

        // 先把主队列里剩下的全部取出并尝试写入
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            List<AuditLogEntity> batch = new ArrayList<>(5000);
            queue.drainTo(batch, 5000);
            if (batch.isEmpty()) break;
            accumulateMonthlyTokens(batch);
            if (!dbWriteEnabled) continue;
            try {
                auditLogMapper.batchInsert(batch);
                drained += batch.size();
            } catch (Exception e) {
                retryBuffer.offerFailed(batch);
            }
        }
        // 再把重试缓冲区里的积压尽力写完（忽略退避，退出在即）
        if (dbWriteEnabled) {
            drained += retryBuffer.drainAll(auditLogMapper::batchInsert, deadline);
        }

        int remaining = queue.size() + retryBuffer.pendingRows();
        if (remaining > 0) {
            log.error("[AuditService] 优雅退出结束，仍有 {} 条审计未落库，将随进程退出丢失", remaining);
        } else {
            log.info("[AuditService] 优雅退出刷盘完成，本次落库 {} 条", drained);
        }
    }

    /**
     * 进程退出兜底。
     *
     * <p>与优雅退出端点两处都做：平台调用端点后到 SIGTERM 之间通常有几十秒摘流量窗口，
     * 那里刷盘最从容；但如果平台没调端点、或直接 SIGTERM（本地重启、异常退出），
     * 就只剩这里。{@code @PreDestroy} 阶段连接池可能已在关闭，所以它是兜底而非主路径。
     */
    @jakarta.annotation.PreDestroy
    public void onShutdown() {
        drainForShutdown();
    }

    /**
     * 将本批次的 token 消耗按 (appId, modelId) 分组汇总，批量写入 Redis 月度计数器。
     *
     * <p>不在每条审计记录入队时单独累加，原因：
     * <ol>
     *   <li>每次请求都写 Redis 会带来大量 INCRBY 往返（去 Lua 前是 EVAL，代价同理）；</li>
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
                                 long latencyMs, int inputTokens, int outputTokens,
                                 String tokenSource, String errorMsg) {
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
        entry.setTokenSource(tokenSource);
        entry.setErrorMsg(errorMsg);
        entry.setCreateTime(LocalDateTime.now());
        return entry;
    }

}
