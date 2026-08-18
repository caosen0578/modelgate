package com.pab.ficc.ibp.modelgate.server.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * {@code @Scheduled} 定时任务专用线程池。
 *
 * <p><b>2026-08-18 压测优化新增</b>：此前 {@code ModelGateApplication} 只有裸的
 * {@code @EnableScheduling}，没有自定义 {@link TaskScheduler} Bean，Spring Boot 默认给
 * {@code @Scheduled} 任务分配的是单线程调度器——全项目 5 个定时任务（AuditService.flush /
 * PayloadService.flush 各 2s 一次、PayloadCleanupService/AuditCleanupService 每日清理、
 * GatewayConfigService.refresh 30s 一次）互相排队执行，其中两个高频批量写库任务
 * （Audit/Payload flush）经常因此被彼此拖慢，间接放大了压测里持续负载下的长尾延迟。
 * 池大小按当前任务数量 + 1 点余量给 6，不需要动态扩容。
 */
@Configuration
public class SchedulingConfig {

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(6);
        scheduler.setThreadNamePrefix("mg-scheduled-");
        scheduler.setErrorHandler(t ->
                org.slf4j.LoggerFactory.getLogger(SchedulingConfig.class)
                        .warn("[Scheduling] 定时任务执行异常（不影响其它任务）", t));
        scheduler.initialize();
        return scheduler;
    }
}
