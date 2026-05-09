package com.pab.ficc.idp.modelgate.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.idp.modelgate.entity.AuditLog;
import com.pab.ficc.idp.modelgate.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;
    private final AuditLogMapper auditLogMapper;

    @Value("${modelgate.audit.topic:modelgate-audit-log}")
    private String auditTopic;

    @Value("${modelgate.audit.enabled:true}")
    private boolean auditEnabled;

    @Value("${modelgate.audit.db-write:true}")
    private boolean dbWriteEnabled;

    @Async
    public void log(String appId, String modelId, String credType, String clientIp,
                    String requestPath, int httpStatus, long latencyMs,
                    int inputTokens, int outputTokens, String errorMsg, String traceId) {
        if (!auditEnabled) return;

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

        if (dbWriteEnabled) {
            try {
                auditLogMapper.insert(entry);
            } catch (Exception e) {
                log.warn("[AuditService] DB write failed: {}", e.getMessage());
            }
        }

        sendToKafka(entry);
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
                    .whenComplete((result, ex) -> {
                        if (ex != null) log.warn("[AuditService] Kafka send failed: {}", ex.getMessage());
                    });
        } catch (JsonProcessingException e) {
            log.warn("[AuditService] Serialize audit record failed", e);
        }
    }
}
