package com.pab.ficc.ibp.modelgate.server.service;

import com.pab.ficc.ibp.modelgate.server.mapper.RequestPayloadMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 报文表保留期清理。
 *
 * <p><b>保留期独立于审计</b>（默认 7 天 vs 审计 90 天）：报文行含 LONGTEXT，
 * 单行体积是审计记录的几十到上千倍，按审计的保留期存会让磁盘增速失控。
 * 排查场景也多是"最近几天出的问题"，长期留存价值低。
 *
 * <p>与 {@code AuditCleanupService} 同样采用<b>分批 + LIMIT</b> 删除：
 * 一次性 DELETE 大批量 LONGTEXT 行会长时间持锁、撑大 binlog，可能拖慢在线业务。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PayloadCleanupService {

    /** 单批删除条数；报文行大，取值明显小于审计清理的批大小 */
    private static final int BATCH_SIZE = 500;

    /** 单次任务最多删除的批次数，防止历史积压过多时一次跑太久 */
    private static final int MAX_BATCHES = 200;

    private final RequestPayloadMapper payloadMapper;

    @Value("${modelgate.payload-log.retention-days:7}")
    private int retentionDays;

    @Scheduled(cron = "${modelgate.payload-log.cleanup-cron:0 30 2 * * ?}")
    public void cleanup() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        int totalDeleted = 0;
        int deleted;
        int batches = 0;
        try {
            do {
                deleted = payloadMapper.deleteBefore(cutoff, BATCH_SIZE);
                totalDeleted += deleted;
                batches++;
            } while (deleted >= BATCH_SIZE && batches < MAX_BATCHES);

            if (totalDeleted > 0) {
                log.info("[PayloadCleanup] 清理完成: 保留 {} 天, 删除 {} 条, cutoff={}",
                        retentionDays, totalDeleted, cutoff);
            }
            if (batches >= MAX_BATCHES) {
                // 说明积压量超出单次任务处理能力，需要人工关注（可能是保留期刚调小，或报文量激增）
                log.warn("[PayloadCleanup] 达到单次批次上限 {}，剩余记录留待下次清理", MAX_BATCHES);
            }
        } catch (Exception e) {
            log.error("[PayloadCleanup] 清理失败: {}", e.getMessage(), e);
        }
    }
}
