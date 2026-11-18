package com.pab.ficc.ibp.modelgate.server.service.evaluation;

import com.pab.ficc.ibp.modelgate.server.domain.vo.PayloadDetailVO;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.Disposable;
import reactor.core.Disposables;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class EvaluationWorker implements AutoCloseable {
    private static final int LEASE_SECONDS = 45;
    private final EvaluationStore store;
    private final EvaluationRunner runner;
    private final String owner = UUID.randomUUID().toString();
    private final Map<String, Disposable.Swap> active = new ConcurrentHashMap<>();
    private final Scheduler execution;
    private final int slots;
    private final boolean enabled;
    private boolean reportedUnavailable;

    public EvaluationWorker(EvaluationStore store, EvaluationRunner runner,
            @Value("${modelgate.evaluation.worker-slots:2}") int slots,
            @Value("${modelgate.evaluation.enabled:true}") boolean enabled) {
        this.store = store; this.runner = runner; this.slots = Math.max(1, slots); this.enabled = enabled;
        execution = Schedulers.newBoundedElastic(this.slots, 100, "mg-evaluation");
    }

    @Scheduled(fixedDelayString = "${modelgate.evaluation.poll-ms:1000}")
    public void tick() {
        if (!enabled) return;
        try {
            active.forEach((id, call) -> {
                if (!store.heartbeat(id, owner, LEASE_SECONDS)) call.dispose();
            });
            store.recover();
            if (active.size() >= slots) return;
            for (var task : store.rows("SELECT id FROM mg_eval_task WHERE status IN ('QUEUED','RUNNING') ORDER BY create_time,id LIMIT 100")) {
                while (active.size() < slots) {
                    var claim = store.claim(String.valueOf(task.get("id")), owner, LEASE_SECONDS);
                    if (claim == null) break;
                    execute(claim);
                }
            }
            reportedUnavailable = false;
        } catch (RuntimeException e) {
            if (!reportedUnavailable) log.warn("[Evaluation] 调度暂不可用，请核对评测表与数据库连接: {}", e.getClass().getSimpleName());
            reportedUnavailable = true;
        }
    }

    @SuppressWarnings("unchecked")
    private void execute(Map<String, Object> row) {
        String id = String.valueOf(row.get("id"));
        String taskId = String.valueOf(row.get("taskId"));
        var slot = Disposables.swap();
        active.put(id, slot);
        var task = (Map<String, Object>) row.get("task");
        var sample = (Map<String, Object>) row.get("sample");
        slot.update(reactor.core.publisher.Mono.defer(() -> runner.run(task,
                        store.read(sample.get("snapshotJson"), PayloadDetailVO.class), String.valueOf(row.get("requestJson")),
                        request -> store.saveRequest(id, owner, request)))
                .subscribeOn(execution)
                .onErrorResume(error -> reactor.core.publisher.Mono.just(EvaluationRunner.failure(error, 0)))
                .publishOn(execution)
                .doOnNext(result -> store.finish(taskId, id, owner, result.status(), result))
                .doFinally(signal -> active.remove(id, slot))
                .subscribe(result -> {}, error -> log.warn("[Evaluation] 结果未能保存，将由租约恢复为不确定状态: resultId={}", id)));
    }

    @PreDestroy
    @Override
    public void close() {
        active.values().forEach(Disposable::dispose);
        execution.dispose();
    }
}
