package com.pab.ficc.ibp.modelgate.server.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLog;
import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.concurrent.ListenableFuture;
import org.springframework.util.concurrent.SettableListenableFuture;

import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock KafkaTemplate<String, String> kafkaTemplate;
    @Mock AuditLogMapper auditLogMapper;
    @Mock RateLimitService rateLimitService;

    @InjectMocks AuditService auditService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        ReflectionTestUtils.setField(auditService, "auditTopic", "modelgate-audit-log");
        ReflectionTestUtils.setField(auditService, "auditEnabled", true);
        ReflectionTestUtils.setField(auditService, "dbWriteEnabled", true);
        ReflectionTestUtils.setField(auditService, "bufferSize", 1000);
        ReflectionTestUtils.setField(auditService, "objectMapper", new ObjectMapper().findAndRegisterModules());

        auditService.init();

        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.set(null);
        lenient().when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn((ListenableFuture) future);
    }

    // -------------------------------------------------------------------------
    // log — queue behavior
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("log — offers to queue, does NOT call mapper.insert directly")
    void log_offersToQueue_noDirectInsert() {
        auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1",
                "/v1/chat", 200, 100L, 50, 100, null, "trace-1");

        verifyNoInteractions(auditLogMapper);
    }

    @Test
    @DisplayName("log — sends to Kafka immediately (not deferred to flush)")
    void log_sendsToKafkaImmediately() {
        auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1",
                "/v1/chat", 200, 100L, 50, 100, null, "trace-1");

        verify(kafkaTemplate).send(eq("modelgate-audit-log"), eq("app-1"), anyString());
    }

    // -------------------------------------------------------------------------
    // flush — batch insert
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("flush — drains queue and calls batchInsert")
    void flush_callsBatchInsert() {
        auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1", "/v1/chat", 200, 100L, 10, 20, null, "t1");
        auditService.log("app-2", "claude-3", "ak_sk", "10.0.0.1", "/v1/chat", 200, 200L, 30, 40, null, "t2");

        auditService.flush();

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<AuditLog>> captor = ArgumentCaptor.forClass(List.class);
        verify(auditLogMapper).batchInsert(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
    }

    @Test
    @DisplayName("flush — empties the queue after processing")
    void flush_emptiesQueue() {
        auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1", "/v1/chat", 200, 100L, 0, 0, null, "t1");

        auditService.flush();
        auditService.flush();  // second flush should be a no-op

        verify(auditLogMapper, times(1)).batchInsert(any());
    }

    @Test
    @DisplayName("flush — batchInsert throws → no exception propagated, queue continues")
    void flush_batchInsertThrows_noException() {
        doThrow(new RuntimeException("DB down")).when(auditLogMapper).batchInsert(any());
        auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1", "/v1/chat", 200, 100L, 0, 0, null, "t1");

        assertThatCode(() -> auditService.flush()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("flush — accumulates monthly tokens aggregated per app+model")
    void flush_accumulatesMonthlyTokens() {
        auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1", "/v1/chat", 200, 100L, 100, 200, null, "t1");
        auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1", "/v1/chat", 200, 100L, 50, 50, null, "t2");

        auditService.flush();

        // Both entries for same app+model → single addMonthlyTokens call with 400 tokens
        verify(rateLimitService).addMonthlyTokens("app-1", "gpt-4", 400L);
    }

    @Test
    @DisplayName("flush — entries with zero tokens are not tracked")
    void flush_zeroTokenEntries_notTracked() {
        auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1", "/v1/chat", 401, 10L, 0, 0, "err", "t1");

        auditService.flush();

        verify(rateLimitService, never()).addMonthlyTokens(any(), any(), anyLong());
    }

    // -------------------------------------------------------------------------
    // audit disabled
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("log — auditEnabled=false → skips queue and Kafka")
    void log_auditDisabled_doesNothing() {
        ReflectionTestUtils.setField(auditService, "auditEnabled", false);

        auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1",
                "/v1/chat", 200, 100L, 50, 100, null, "t1");

        verifyNoInteractions(auditLogMapper, kafkaTemplate);
    }

    // -------------------------------------------------------------------------
    // db write disabled
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("log — dbWriteEnabled=false → skips queue but sends Kafka")
    void log_dbWriteDisabled_onlyKafka() {
        ReflectionTestUtils.setField(auditService, "dbWriteEnabled", false);

        auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1",
                "/v1/chat", 200, 100L, 50, 100, null, "t1");

        verifyNoInteractions(auditLogMapper);
        verify(kafkaTemplate).send(anyString(), anyString(), anyString());
    }

    // -------------------------------------------------------------------------
    // queue full
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("log — queue full → drops entry without exception")
    void log_queueFull_dropsWithoutException() {
        ReflectionTestUtils.setField(auditService, "bufferSize", 1);
        auditService.init();  // reinitialize with capacity=1

        auditService.log("app-1", "m1", "api_key", "127.0.0.1", "/v1/chat", 200, 100L, 0, 0, null, "t1");
        assertThatCode(() ->
                auditService.log("app-2", "m1", "api_key", "127.0.0.1", "/v1/chat", 200, 100L, 0, 0, null, "t2"))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Kafka failures
    // -------------------------------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    @DisplayName("log — Kafka send callback fails → no exception thrown")
    void log_kafkaCallbackFails_noException() {
        SettableListenableFuture<SendResult<String, String>> future = new SettableListenableFuture<>();
        future.setException(new RuntimeException("Kafka down"));
        when(kafkaTemplate.send(anyString(), anyString(), anyString()))
                .thenReturn((ListenableFuture) future);

        assertThatCode(() ->
                auditService.log("app-1", "gpt-4", "api_key", "127.0.0.1",
                        "/v1/chat", 200, 100L, 50, 100, null, "t1"))
                .doesNotThrowAnyException();
    }
}
