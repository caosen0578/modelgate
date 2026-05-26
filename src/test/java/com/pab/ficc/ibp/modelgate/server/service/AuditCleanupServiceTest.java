package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditCleanupServiceTest {

    @Mock AuditLogMapper auditLogMapper;

    @InjectMocks AuditCleanupService auditCleanupService;

    @BeforeEach
    void setup() {
        ReflectionTestUtils.setField(auditCleanupService, "retentionDays", 90);
    }

    // -------------------------------------------------------------------------
    // cleanup — cutoff date
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("cleanup — cutoff is retentionDays ago")
    void cleanup_cutoffIsRetentionDaysAgo() {
        when(auditLogMapper.deleteBefore(any(LocalDateTime.class), anyInt())).thenReturn(0);

        LocalDateTime before = LocalDateTime.now().minusDays(90);
        auditCleanupService.cleanup();
        LocalDateTime after = LocalDateTime.now().minusDays(90);

        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(auditLogMapper).deleteBefore(cutoffCaptor.capture(), eq(10000));

        LocalDateTime captured = cutoffCaptor.getValue();
        assertThat(captured).isBetween(before.minusSeconds(1), after.plusSeconds(1));
    }

    // -------------------------------------------------------------------------
    // cleanup — single batch (no loop)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("cleanup — deleted < 10000 → single batch, no loop")
    void cleanup_singleBatch() {
        when(auditLogMapper.deleteBefore(any(), anyInt())).thenReturn(5000);

        auditCleanupService.cleanup();

        verify(auditLogMapper, times(1)).deleteBefore(any(), eq(10000));
    }

    // -------------------------------------------------------------------------
    // cleanup — multiple batches (loop)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("cleanup — first batch full, second partial → loops twice")
    void cleanup_multipleBatches() {
        when(auditLogMapper.deleteBefore(any(), anyInt()))
                .thenReturn(10000)  // first batch full
                .thenReturn(3000);  // second batch partial → stop

        auditCleanupService.cleanup();

        verify(auditLogMapper, times(2)).deleteBefore(any(), eq(10000));
    }

    @Test
    @DisplayName("cleanup — three full batches then empty → loops four times")
    void cleanup_threeFullBatches() {
        when(auditLogMapper.deleteBefore(any(), anyInt()))
                .thenReturn(10000)
                .thenReturn(10000)
                .thenReturn(10000)
                .thenReturn(0);

        auditCleanupService.cleanup();

        verify(auditLogMapper, times(4)).deleteBefore(any(), eq(10000));
    }

    // -------------------------------------------------------------------------
    // cleanup — no records
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("cleanup — no records to delete → single call, no exception")
    void cleanup_noRecords_noException() {
        when(auditLogMapper.deleteBefore(any(), anyInt())).thenReturn(0);

        assertThatCode(() -> auditCleanupService.cleanup()).doesNotThrowAnyException();
        verify(auditLogMapper, times(1)).deleteBefore(any(), eq(10000));
    }
}
