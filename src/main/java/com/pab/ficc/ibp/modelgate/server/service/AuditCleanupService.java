package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.mapper.AuditLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuditCleanupService {

    private static final int BATCH_SIZE = 10000;
    private static final long SLEEP_BETWEEN_BATCHES_MS = 200L;

    private final AuditLogMapper auditLogMapper;

    @Value("${modelgate.audit.retention-days:90}")
    private int retentionDays;

    @Scheduled(cron = "${modelgate.audit.cleanup-cron:0 0 2 * * ?}")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        log.info("[AuditCleanup] Deleting audit logs before {}", cutoff);

        int totalDeleted = 0;
        int deleted;
        do {
            deleted = auditLogMapper.deleteBefore(cutoff, BATCH_SIZE);
            totalDeleted += deleted;
            if (deleted >= BATCH_SIZE) {
                try {
                    Thread.sleep(SLEEP_BETWEEN_BATCHES_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } while (deleted >= BATCH_SIZE);

        log.info("[AuditCleanup] Deleted {} records total", totalDeleted);
    }
}
