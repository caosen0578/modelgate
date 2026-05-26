package com.pab.ficc.ibp.modelgate.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLog;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AuditLogMapper auditLogMapper;
    private final RateLimitService rateLimitService;

    @Value("${modelgate.audit.topic:modelgate-audit-log}")
    private String auditTopic;

    @Value("${modelgate.audit.enabled:true}")
    private boolean auditEnabled;

    @Value("${modelgate.audit.db-write:true}")
    private boolean dbWriteEnabled;

    @Value("${modelgate.audit.buffer-size:200000}")
    private int bufferSize;

    private LinkedBlockingQueue<AuditLog> queue;

    @PostConstruct
    public void init() {
        queue = new LinkedBlockingQueue<>(bufferSize);
    }

    public void log(String appId, String modelId, String credType, String clientIp,
                    String requestPath, int httpStatus, long latencyMs,
                    int inputTokens, int outputTokens, String errorMsg, String traceId) {
        if (!auditEnabled) return;

        AuditLog entry = buildEntry(traceId, appId, modelId, credType, clientIp,
                requestPath, httpStatus, latencyMs, inputTokens, outputTokens, errorMsg);

        if (dbWriteEnabled) {
            boolean offered = queue.offer(entry);
            if (!offered) {
                log.warn("[AuditService] Queue full, dropping audit log traceId={}", traceId);
            }
        }

        sendToKafka(entry);
    }

    @Scheduled(fixedDelayString = "${modelgate.audit.flush-interval-ms:2000}")
    public void flush() {
        if (!dbWriteEnabled || queue == null || queue.isEmpty()) return;
        List<AuditLog> batch = new ArrayList<>(5000);
        queue.drainTo(batch, 5000);
        if (batch.isEmpty()) return;

        try {
            auditLogMapper.batchInsert(batch);
        } catch (Exception e) {
            log.warn("[AuditService] Batch insert failed size={}: {}", batch.size(), e.getMessage());
        }

        accumulateMonthlyTokens(batch);
    }

    private void accumulateMonthlyTokens(List<AuditLog> batch) {
        // Aggregate tokens per app+model to minimize Redis calls
        record Key(String appId, String modelId) {}
        Map<Key, Long> totals = new HashMap<>();
        for (AuditLog entry : batch) {
            if (entry.getAppId() == null || entry.getModelId() == null) continue;
            long tokens = (entry.getInputTokens() != null ? entry.getInputTokens() : 0)
                    + (entry.getOutputTokens() != null ? entry.getOutputTokens() : 0);
            if (tokens <= 0) continue;
            totals.merge(new Key(entry.getAppId(), entry.getModelId()), tokens, Long::sum);
        }
        totals.forEach((k, tokens) -> rateLimitService.addMonthlyTokens(k.appId(), k.modelId(), tokens));
    }

    private AuditLog buildEntry(String traceId, String appId, String modelId, String credType,
                                 String clientIp, String requestPath, int httpStatus,
                                 long latencyMs, int inputTokens, int outputTokens, String errorMsg) {
        AuditLog entry = new AuditLog();
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
        entry.setCreatedAt(LocalDateTime.now());
        return entry;
    }

    private void sendToKafka(AuditLog entry) {
        try {
            Map<String, Object> record = new HashMap<>();
            record.put("traceId", entry.getTraceId());
            record.put("appId", entry.getAppId());
            record.put("modelId", entry.getModelId());
            record.put("credType", entry.getCredType());
            record.put("clientIp", entry.getClientIp());
            record.put("requestPath", entry.getRequestPath());
            record.put("httpStatus", entry.getHttpStatus());
            record.put("latencyMs", entry.getLatencyMs());
            record.put("inputTokens", entry.getInputTokens());
            record.put("outputTokens", entry.getOutputTokens());
            record.put("errorMsg", entry.getErrorMsg());
            record.put("timestamp", entry.getCreatedAt().toString());

            String json = objectMapper.writeValueAsString(record);
            kafkaTemplate.send(auditTopic, entry.getAppId(), json)
                    .addCallback(
                            result -> { /* 成功无需处理 */ },
                            ex -> log.warn("[AuditService] Kafka send failed: {}", ex.getMessage())
                    );
        } catch (JsonProcessingException e) {
            log.warn("[AuditService] Serialize audit record failed", e);
        }
    }
}
