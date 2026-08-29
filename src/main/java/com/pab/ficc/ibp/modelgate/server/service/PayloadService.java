package com.pab.ficc.ibp.modelgate.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.RequestPayloadEntity;
import com.pab.ficc.ibp.modelgate.server.mapper.RequestPayloadMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 请求/响应报文异步落库。
 *
 * <p>结构刻意与 {@link AuditService} 保持一致（内存队列 + 定时批量 INSERT），
 * 因为要满足同一条硬约束：<b>记录过程绝不能阻塞正常通讯</b>。具体保障：
 * <ul>
 *   <li><b>入队用 {@code offer} 而非 {@code put}</b>——队列满时直接丢弃报文并计数，
 *       <b>绝不阻塞调用线程</b>。报文是旁路数据，丢比拖慢业务好。</li>
 *   <li><b>SSE 拼接与 JSON 解析全部在消费端线程</b>（{@code flush} 的定时线程），
 *       采集侧只把原始字节丢进队列。响应体可能很大，在 Netty 事件循环里解析会卡住
 *       该 EventLoop 上的所有连接。</li>
 *   <li><b>批量写库失败不再直接丢弃</b>——整批转入 {@link PendingWriteBuffer} 重试
 *       （指数退避 + 毒药记录隔离）。原先"失败只记 WARN"的写法会让数据在离开队列后
 *       永久消失，而 DB 抖动恰恰是最常见的情况。重试全部发生在消费端线程上，
 *       仍然不反压到业务链路。</li>
 * </ul>
 *
 * <p>队列容量、批大小、刷盘间隔、保留期均可配置，见 {@code modelgate.payload-log.*}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayloadService {

    private final RequestPayloadMapper payloadMapper;
    private final UpstreamResponseParser responseParser;
    private final ObjectMapper objectMapper;
    private final RequestHeaderSnapshotter requestHeaderSnapshotter;

    /** 全局总开关。授权级开关控制"哪些 app×模型记"，本开关是磁盘告警时的一键止血手段。 */
    @Value("${modelgate.payload-log.enabled:true}")
    private boolean enabled;

    /** 队列容量。报文体积远大于审计记录，容量取审计的 1/10 以控制堆占用。 */
    @Value("${modelgate.payload-log.buffer-size:20000}")
    private int bufferSize;

    /** 单批落库条数。报文含 LONGTEXT，批次过大会撑爆 max_allowed_packet。 */
    @Value("${modelgate.payload-log.batch-size:200}")
    private int batchSize;

    private LinkedBlockingQueue<PendingPayload> queue;
    private final AtomicLong droppedTotal = new AtomicLong();

    /** 写库失败后的重试缓冲区，容量与主队列一致 */
    private PendingWriteBuffer<RequestPayloadEntity> retryBuffer;

    /** 优雅退出刷盘时限，与 AuditService 一致 */
    private static final long SHUTDOWN_DRAIN_TIMEOUT_MS = 15_000;

    /**
     * 待解析的报文。
     *
     * <p>入队的是<b>原始字节</b>而非解析结果——解析要留到消费端做，这是不阻塞的关键。
     */
    private record PendingPayload(String traceId, String appId, String modelId,
                                  String trafficType, String serviceId,
                                  String requestMethod, String requestPath,
                                  Map<String, String> requestHeaders,
                                  byte[] requestBytes, byte[] responseBytes,
                                  boolean stream, boolean truncated, Integer upstreamStatus,
                                  LocalDateTime createTime) {}

    @PostConstruct
    public void init() {
        queue = new LinkedBlockingQueue<>(bufferSize);
        retryBuffer = new PendingWriteBuffer<>("PayloadService", bufferSize,
                e -> String.format("traceId=%s appId=%s modelId=%s time=%s",
                        e.getTraceId(), e.getAppId(), e.getModelId(), e.getCreateTime()));
        log.info("[PayloadService] 报文队列就绪, enabled={}, capacity={}, batchSize={}",
                enabled, bufferSize, batchSize);
    }

    /**
     * 提交一条报文（非阻塞，可在响应式链路的任意线程调用）。
     *
     * <p>本方法只做全部 Header 的有界快照与入队，<b>不做 JSON 序列化或报文解析</b>。
     * 请求线程单次遍历入站 Header，复制量受总字符上限约束，开销不随请求/响应报文体积增长。
     */
    public void submit(String traceId, String appId, String modelId,
                       String requestMethod, String requestPath,
                       HttpHeaders requestHeaders,
                       byte[] requestBytes, byte[] responseBytes,
                       boolean stream, boolean truncated, Integer upstreamStatus) {
        submit(traceId, appId, modelId, "model", null, requestMethod, requestPath, requestHeaders,
                requestBytes, responseBytes, stream, truncated, upstreamStatus);
    }

    /** 带流量类型的重载；服务流量传 {@code ("service", serviceId)}，modelId 传 null。 */
    public void submit(String traceId, String appId, String modelId,
                       String trafficType, String serviceId,
                       String requestMethod, String requestPath,
                       HttpHeaders requestHeaders,
                       byte[] requestBytes, byte[] responseBytes,
                       boolean stream, boolean truncated, Integer upstreamStatus) {
        if (!enabled) {
            return;
        }
        // 只在报文开关已命中后做全部 Header 的有界快照；JSON 序列化仍留在消费线程。
        Map<String, String> headerSnapshot = requestHeaderSnapshotter.capture(requestHeaders);
        boolean offered = queue.offer(new PendingPayload(traceId, appId, modelId,
                trafficType == null ? "model" : trafficType, serviceId,
                requestMethod, truncatePath(requestPath), headerSnapshot,
                requestBytes, responseBytes, stream, truncated, upstreamStatus, LocalDateTime.now()));
        if (!offered) {
            // 满了就丢，绝不阻塞业务线程；累计数打出来，否则报文断流时无人知晓
            log.warn("[PayloadService] 报文队列已满，丢弃本条: traceId={}, 累计丢弃={}",
                    traceId, droppedTotal.incrementAndGet());
        }
    }

    /**
     * 定时消费：解析 + 批量落库。
     *
     * <p>跑在 Spring 的调度线程上，与业务请求线程完全隔离——
     * 这里做多重的活都不会影响正在进行的转发。
     */
    @Scheduled(fixedDelayString = "${modelgate.payload-log.flush-interval-ms:2000}")
    public void flush() {
        if (!enabled || queue == null) {
            return;
        }
        // 先重试积压，再处理新数据；否则 DB 恢复后旧数据永远排不上队
        retryBuffer.retryDue(payloadMapper::batchInsert);

        if (queue.isEmpty()) {
            return;
        }
        List<PendingPayload> batch = new ArrayList<>(batchSize);
        queue.drainTo(batch, batchSize);
        if (batch.isEmpty()) {
            return;
        }

        List<RequestPayloadEntity> entities = new ArrayList<>(batch.size());
        for (PendingPayload pending : batch) {
            try {
                entities.add(toEntity(pending));
            } catch (Exception e) {
                // 单条解析失败不影响整批
                log.warn("[PayloadService] 报文解析失败，跳过该条: traceId={}: {}",
                        pending.traceId(), e.getMessage());
            }
        }
        if (entities.isEmpty()) {
            return;
        }
        writeOrRetry(entities);
    }

    /** 写入，失败则转入重试缓冲区而不是丢弃 */
    private void writeOrRetry(List<RequestPayloadEntity> entities) {
        try {
            payloadMapper.batchInsert(entities);
            log.debug("[PayloadService] 报文落库 {} 条", entities.size());
        } catch (Exception e) {
            log.warn("[PayloadService] 报文批量落库失败，转入重试队列: {} 条, 原因={}",
                    entities.size(), e.getMessage());
            retryBuffer.offerFailed(entities);
        }
    }

    /**
     * 优雅退出时把内存里未落库的报文尽力写完。
     *
     * <p>与 {@code AuditService.drainForShutdown} 同一套逻辑与时限。报文的价值低于审计，
     * 但发版时整队丢弃同样没有道理——尤其排查现场往往就在最近这几分钟的数据里。
     */
    public void drainForShutdown() {
        if (!enabled || queue == null) {
            return;
        }
        long deadline = System.currentTimeMillis() + SHUTDOWN_DRAIN_TIMEOUT_MS;
        while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
            List<PendingPayload> batch = new ArrayList<>(batchSize);
            queue.drainTo(batch, batchSize);
            if (batch.isEmpty()) break;
            List<RequestPayloadEntity> entities = new ArrayList<>(batch.size());
            for (PendingPayload pending : batch) {
                try {
                    entities.add(toEntity(pending));
                } catch (Exception e) {
                    log.warn("[PayloadService] 退出刷盘时解析失败，跳过: traceId={}", pending.traceId());
                }
            }
            if (!entities.isEmpty()) {
                writeOrRetry(entities);
            }
        }
        retryBuffer.drainAll(payloadMapper::batchInsert, deadline);

        int remaining = queue.size() + retryBuffer.pendingRows();
        if (remaining > 0) {
            log.error("[PayloadService] 优雅退出结束，仍有约 {} 条报文未落库，将随进程退出丢失", remaining);
        }
    }

    /** 进程退出兜底，理由同 AuditService.onShutdown */
    @jakarta.annotation.PreDestroy
    public void onShutdown() {
        drainForShutdown();
    }

    /** 解析发生在这里——消费端线程，与业务链路无关。 */
    private RequestPayloadEntity toEntity(PendingPayload p) {
        UpstreamResponseParser.ParsedResponse parsed =
                responseParser.parse(p.responseBytes(), p.stream(), p.requestBytes());

        RequestPayloadEntity e = new RequestPayloadEntity();
        e.setTraceId(p.traceId());
        e.setAppId(p.appId());
        e.setModelId(p.modelId());
        e.setTrafficType(p.trafficType());
        e.setServiceId(p.serviceId());
        e.setIsStream(p.stream());
        e.setRequestMethod(p.requestMethod());
        e.setRequestPath(p.requestPath());
        e.setRequestHeaders(toJson(p.requestHeaders()));
        e.setRequestBody(toText(p.requestBytes()));
        e.setResponseRaw(toText(p.responseBytes()));
        e.setResponseText(parsed.responseText());
        e.setFinishReason(parsed.finishReason());
        e.setUpstreamStatus(p.upstreamStatus());
        e.setTruncated(p.truncated() ? "response" : "none");
        e.setCreateTime(p.createTime());
        return e;
    }

    /**
     * 路径列是 VARCHAR(512)，超长直接截断。
     *
     * <p>不截会让整条 INSERT 因 data too long 失败——为了一个"回放时方便"的辅助字段
     * 丢掉整条报文留档，代价与收益完全不成比例。截断只影响回放的便利性。
     */
    private static String truncatePath(String path) {
        if (path == null) {
            return null;
        }
        return path.length() <= 512 ? path : path.substring(0, 512);
    }

    private static String toText(byte[] bytes) {
        return bytes == null || bytes.length == 0 ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    /** JSON 序列化只在定时消费线程执行，不占用 Netty EventLoop。 */
    private String toJson(Map<String, String> headers) {
        if (headers == null || headers.isEmpty()) return null;
        try {
            return objectMapper.writeValueAsString(headers);
        } catch (JsonProcessingException e) {
            // Header 都是 String，正常不会失败；即使遇到框架异常也只丢本字段，不丢整条报文。
            log.warn("[PayloadService] 请求头序列化失败，已跳过请求头字段: {}", e.getMessage());
            return null;
        }
    }

    /** 供监控/排查：当前积压与累计丢弃数。 */
    public long queueSize() {
        return queue == null ? 0 : queue.size();
    }

    public long droppedTotal() {
        return droppedTotal.get();
    }
}
