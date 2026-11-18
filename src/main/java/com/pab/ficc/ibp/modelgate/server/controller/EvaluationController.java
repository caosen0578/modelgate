package com.pab.ficc.ibp.modelgate.server.controller;

import com.pab.ficc.ibp.modelgate.server.common.AdminScheduler;
import com.pab.ficc.ibp.modelgate.server.common.RecordCsv;
import com.pab.ficc.ibp.modelgate.server.constant.UrlConstant;
import com.pab.ficc.ibp.modelgate.server.domain.Result;
import com.pab.ficc.ibp.modelgate.server.service.evaluation.EvaluationService;
import com.pab.ficc.ibp.modelgate.server.service.evaluation.EvaluationStore;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

@RestController
@RequestMapping(UrlConstant.MG_EVALUATION)
public class EvaluationController {
    private final EvaluationService service;
    private final EvaluationStore store;

    public EvaluationController(EvaluationService service, EvaluationStore store) {
        this.service = service; this.store = store;
    }

    @ModelAttribute
    public void noStore(ServerHttpResponse response) {
        response.getHeaders().setCacheControl(CacheControl.noStore());
    }

    @GetMapping("/datasets")
    public Mono<Result<Map<String, Object>>> datasets(@RequestParam(value="pageIndex", defaultValue="1") int page,
            @RequestParam(value="pageSize", defaultValue="20") int size) {
        return read(() -> service.datasets(page, size));
    }

    @GetMapping("/dataset")
    public Mono<Result<Map<String, Object>>> dataset(@RequestParam("id") String id) { return read(() -> service.dataset(id)); }

    @GetMapping("/sample")
    public Mono<Result<Map<String, Object>>> sample(@RequestParam("id") String id) { return read(() -> service.caseDetail(id)); }

    @PostMapping("/validate")
    public Mono<Result<List<Map<String, Object>>>> validate(@RequestBody EvaluationService.DatasetInput input) {
        return read(() -> service.validate(new EvaluationService.DatasetInput(input.name(), input.payloadIds(), false)));
    }

    @PostMapping("/validate-personal")
    public Mono<Result<List<Map<String, Object>>>> validatePersonal(@RequestBody EvaluationService.DatasetInput input) {
        return read(() -> service.validate(new EvaluationService.DatasetInput(input.name(), input.payloadIds(), true)));
    }

    @PostMapping("/create-dataset")
    public Mono<Result<String>> createDataset(@RequestBody EvaluationService.DatasetInput input) {
        return read(() -> service.createDataset(new EvaluationService.DatasetInput(input.name(), input.payloadIds(), false)));
    }

    @PostMapping("/create-personal-dataset")
    public Mono<Result<String>> createPersonalDataset(@RequestBody EvaluationService.DatasetInput input) {
        return read(() -> service.createDataset(new EvaluationService.DatasetInput(input.name(), input.payloadIds(), true)));
    }

    @PostMapping("/revise-dataset")
    public Mono<Result<String>> reviseDataset(@RequestBody EvaluationService.RevisionInput input) {
        return read(() -> service.revise(input));
    }

    @PostMapping("/import-dataset")
    public Mono<Result<String>> importDataset(@RequestBody Flux<DataBuffer> input) {
        return DataBufferUtils.join(input, 16 * 1024 * 1024).flatMap(buffer -> {
            byte[] bytes = new byte[buffer.readableByteCount()];
            try { buffer.read(bytes); } finally { DataBufferUtils.release(buffer); }
            return read(() -> service.importDataset(store.read(new String(bytes, StandardCharsets.UTF_8),
                    EvaluationService.ImportInput.class)));
        }).switchIfEmpty(Mono.error(EvaluationStore.invalid("请选择非空样本文件")))
                .onErrorMap(DataBufferLimitException.class, error -> EvaluationStore.invalid("导入内容不能超过 16 MB"));
    }

    @PostMapping("/delete-dataset")
    public Mono<Result<Void>> deleteDataset(@RequestParam("id") String id) {
        return read(() -> { service.deleteDataset(id); return null; });
    }

    @GetMapping("/tasks")
    public Mono<Result<Map<String, Object>>> tasks(@RequestParam(value="pageIndex", defaultValue="1") int page,
            @RequestParam(value="pageSize", defaultValue="20") int size) { return read(() -> service.tasks(page, size)); }

    @GetMapping("/task")
    public Mono<Result<Map<String, Object>>> task(@RequestParam("id") String id) { return read(() -> service.task(id)); }

    @PostMapping("/create-task")
    public Mono<Result<String>> createTask(@RequestBody EvaluationService.TaskInput input) {
        return read(() -> service.createTask(input));
    }

    @PostMapping("/cancel")
    public Mono<Result<Void>> cancel(@RequestParam("id") String id) {
        return read(() -> { service.cancel(id); return null; });
    }

    @PostMapping("/retry")
    public Mono<Result<Void>> retry(@RequestBody EvaluationService.RetryInput input) {
        return read(() -> { service.retry(input); return null; });
    }

    @PostMapping("/delete-task")
    public Mono<Result<Void>> deleteTask(@RequestParam("id") String id) {
        return read(() -> { service.deleteTask(id); return null; });
    }

    @GetMapping("/results")
    public Mono<Result<Map<String, Object>>> results(@RequestParam("taskId") String taskId,
            @RequestParam(value="status",defaultValue="") String status,
            @RequestParam(value="verdict",defaultValue="") String verdict,
            @RequestParam(value="pageIndex",defaultValue="1") int page,
            @RequestParam(value="pageSize",defaultValue="20") int size) {
        return read(() -> service.results(taskId, status, verdict, page, size));
    }

    @GetMapping("/result")
    public Mono<Result<Map<String, Object>>> result(@RequestParam("id") String id,
            @RequestParam(value="compareTaskId",required=false) String compareTaskId) {
        return read(() -> {
            var result = service.result(id);
            if (compareTaskId != null && !compareTaskId.isBlank()) {
                var task = store.one("SELECT dataset_id FROM mg_eval_task WHERE id=?", result.get("taskId"));
                var other = store.one("SELECT dataset_id FROM mg_eval_task WHERE id=?", compareTaskId);
                if (!task.get("datasetId").equals(other.get("datasetId"))) throw EvaluationStore.invalid("请选择同一评测集版本的任务");
                var matches = store.rows("SELECT id FROM mg_eval_result WHERE task_id=? AND case_id=? ORDER BY attempt DESC LIMIT 1",
                        compareTaskId, result.get("caseId"));
                if (!matches.isEmpty()) result.put("comparison", service.result(String.valueOf(matches.get(0).get("id"))));
            }
            return result;
        });
    }

    @PostMapping("/score")
    public Mono<Result<Void>> score(@RequestBody EvaluationService.ScoreInput input) {
        return read(() -> { service.score(input); return null; });
    }

    @GetMapping("/export")
    public Mono<ResponseEntity<Flux<byte[]>>> export(@RequestParam("taskId") String taskId,
            @RequestParam(value="format",defaultValue="csv") String format) {
        return Mono.fromCallable(() -> {
            var task = service.task(taskId);
            if (!List.of("csv", "jsonl").contains(format)) throw EvaluationStore.invalid("请选择 CSV 或 JSON Lines");
            if ("jsonl".equals(format)) {
                Flux<byte[]> lines = Flux.<byte[], String>generate(() -> "", (before, sink) -> {
                    var rows = store.rows("SELECT r.id FROM mg_eval_result r WHERE r.task_id=? AND "
                            + EvaluationStore.LATEST + " AND r.id>? ORDER BY r.id LIMIT 1", taskId, before);
                    if (rows.isEmpty()) { sink.complete(); return before; }
                    String id = String.valueOf(rows.get(0).get("id"));
                    sink.next((store.json(Map.of("task", task, "result", service.result(id))) + "\n").getBytes(StandardCharsets.UTF_8));
                    return id;
                }).subscribeOn(AdminScheduler.get());
                return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                        .contentType(MediaType.parseMediaType("application/x-ndjson"))
                        .headers(h -> h.setContentDisposition(ContentDisposition.attachment().filename("evaluation.jsonl").build()))
                        .body(lines);
            }
            return RecordCsv.response("evaluation.csv", "样本,标题,难度,次数,状态,判定,评分,耗时毫秒,输入 Token,输出 Token,Token 来源,完整,评语",
                    before -> store.rows("SELECT r.id,r.attempt,r.status,r.verdict,r.score,r.note,r.latency_ms,"
                                    + "r.input_tokens,r.output_tokens,r.token_source,r.response_complete,c.ordinal_no,c.title,c.difficulty "
                                    + "FROM mg_eval_result r JOIN mg_eval_case c ON c.id=r.case_id WHERE r.task_id=? AND "
                                    + EvaluationStore.LATEST + " AND (? IS NULL OR r.id<?) ORDER BY r.id DESC LIMIT 10",
                            taskId, before == null ? null : String.valueOf(before), before == null ? null : String.valueOf(before)),
                    row -> String.valueOf(row.get("id")),
                    row -> RecordCsv.row(row.get("ordinalNo"), row.get("title"), row.get("difficulty"), row.get("attempt"), row.get("status"), row.get("verdict"),
                            row.get("score"), row.get("latencyMs"), row.get("inputTokens"),
                            row.get("outputTokens"), row.get("tokenSource"), row.get("responseComplete"), row.get("note")));
        }).subscribeOn(AdminScheduler.get()).timeout(AdminScheduler.REQUEST_TIMEOUT);
    }

    private <T> Mono<Result<T>> read(Supplier<T> action) {
        return Mono.fromCallable(() -> Result.success(action.get())).subscribeOn(AdminScheduler.get())
                .timeout(AdminScheduler.REQUEST_TIMEOUT);
    }
}
