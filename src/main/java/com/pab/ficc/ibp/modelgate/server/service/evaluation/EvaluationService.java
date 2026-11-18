package com.pab.ficc.ibp.modelgate.server.service.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelInstanceEntity;
import com.pab.ficc.ibp.modelgate.server.domain.vo.PayloadDetailVO;
import com.pab.ficc.ibp.modelgate.server.mapper.RequestPayloadMapper;
import com.pab.ficc.ibp.modelgate.server.service.AppScopeResolver;
import com.pab.ficc.ibp.modelgate.server.service.ModelService;
import com.pab.ficc.ibp.modelgate.server.common.OperatorProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.pab.ficc.ibp.modelgate.server.service.evaluation.EvaluationStore.invalid;

@Service
public class EvaluationService {
    public record DatasetInput(String name, List<Long> payloadIds, boolean personal) {}
    public record ReferenceInput(String caseId, String referenceText, String difficulty) {}
    public record ImportCase(String title, String prompt, ObjectNode request, String referenceText, String difficulty) {}
    public record ImportInput(String name, List<ImportCase> cases) {}
    public record RevisionInput(String datasetId, List<ReferenceInput> cases) {}
    public record TaskInput(String name, String datasetId, String modelId, ObjectNode overrides,
                            Integer concurrency, Integer timeoutSeconds) {}
    public record RetryInput(String taskId, List<String> resultIds) {}
    public record ScoreInput(String resultId, String verdict, Integer score, String note) {}

    private final EvaluationStore store;
    private final RequestPayloadMapper payloads;
    private final AppScopeResolver scopes;
    private final ModelService models;
    private final OperatorProvider operators;
    private final EvaluationRunner runner;
    private final ObjectMapper mapper;
    private final int maxCases;
    private final int maxSampleBytes;
    private final int maxConcurrency;
    private final int maxDatasetBytes;

    public EvaluationService(EvaluationStore store, RequestPayloadMapper payloads, AppScopeResolver scopes,
            ModelService models, OperatorProvider operators, EvaluationRunner runner, ObjectMapper mapper,
            @Value("${modelgate.evaluation.max-cases:200}") int maxCases,
            @Value("${modelgate.evaluation.max-sample-bytes:4194304}") int maxSampleBytes,
            @Value("${modelgate.evaluation.max-concurrency:4}") int maxConcurrency,
            @Value("${modelgate.evaluation.max-dataset-bytes:67108864}") int maxDatasetBytes) {
        this.store = store; this.payloads = payloads; this.scopes = scopes; this.models = models;
        this.operators = operators; this.runner = runner; this.mapper = mapper;
        this.maxCases = Math.max(1, maxCases); this.maxSampleBytes = Math.max(1024, maxSampleBytes);
        this.maxConcurrency = Math.max(1, maxConcurrency);
        this.maxDatasetBytes = Math.max(1024, maxDatasetBytes);
    }

    public Map<String, Object> datasets(int page, int size) {
        return store.page("SELECT d.*, (SELECT COUNT(*) FROM mg_eval_case c WHERE c.dataset_id=d.id) AS sample_count "
                + "FROM mg_eval_dataset d ORDER BY d.create_time DESC,d.id DESC",
                "SELECT COUNT(*) FROM mg_eval_dataset", page, size);
    }

    public Map<String, Object> dataset(String id) {
        var result = store.one("SELECT * FROM mg_eval_dataset WHERE id=?", id);
        var samples = store.rows("SELECT * FROM mg_eval_case WHERE dataset_id=? ORDER BY ordinal_no", id);
        for (var sample : samples) {
            var snapshot = store.read(sample.remove("snapshotJson"), PayloadDetailVO.class);
            sample.put("traceId", snapshot.getTraceId());
            sample.put("modelId", snapshot.getModelId());
            sample.put("um", snapshot.getUm());
            sample.put("appId", snapshot.getAppId());
            sample.put("sourceTime", snapshot.getCreateTime());
            if (sample.get("referenceText") == null) sample.put("referenceText", baselineAnswer(snapshot));
        }
        result.put("cases", samples);
        return result;
    }

    public Map<String, Object> caseDetail(String id) {
        var row = store.one("SELECT * FROM mg_eval_case WHERE id=?", id);
        if (row.get("referenceText") == null)
            row.put("referenceText", baselineAnswer(store.read(row.get("snapshotJson"), PayloadDetailVO.class)));
        store.decode(row, "snapshotJson");
        return row;
    }

    public List<Map<String, Object>> validate(DatasetInput input) {
        checkSelection(input);
        var checks = new ArrayList<Map<String, Object>>();
        for (Long id : input.payloadIds()) {
            try {
                var snapshot = snapshot(id, input.personal());
                checks.add(Map.of("payloadId", id, "usable", true, "message",
                        "response".equals(snapshot.getTruncated()) ? "历史响应已截断，可执行并保留标记" : "可用"));
            } catch (RuntimeException e) {
                checks.add(Map.of("payloadId", id, "usable", false, "message", String.valueOf(e.getMessage())));
            }
        }
        return checks;
    }

    public String createDataset(DatasetInput input) {
        checkSelection(input);
        String name = name(input.name());
        // 全部预检成功后才落库，原报文在复制期间被清理会返回具体错误。
        List<PayloadDetailVO> samples = new ArrayList<>();
        long bytes = 0;
        for (Long payloadId : input.payloadIds()) {
            var sample = snapshot(payloadId, input.personal());
            bytes += store.json(sample).getBytes(StandardCharsets.UTF_8).length;
            if (bytes > maxDatasetBytes) throw invalid("评测集快照超过配置的总大小上限，请减少样本");
            samples.add(sample);
        }
        return store.atomic(() -> {
            String id = EvaluationStore.id();
            store.update("INSERT INTO mg_eval_dataset(id,root_id,name,version,created_by) VALUES(?,?,?,1,?)",
                    id, id, name, operators.currentOperatorUm());
            for (int i = 0; i < samples.size(); i++) {
                var sample = samples.get(i);
                store.update("INSERT INTO mg_eval_case(id,dataset_id,payload_id,ordinal_no,snapshot_json,reference_text) VALUES(?,?,?,?,?,?)",
                        EvaluationStore.id(), id, sample.getId(), i + 1, store.json(sample), baselineAnswer(sample));
            }
            return id;
        });
    }

    public String revise(RevisionInput input) {
        String root = String.valueOf(store.one("SELECT root_id FROM mg_eval_dataset WHERE id=?", input.datasetId()).get("rootId"));
        return store.atomic(() -> {
            var rootDataset = store.one("SELECT * FROM mg_eval_dataset WHERE id=? FOR UPDATE", root);
            var prior = root.equals(input.datasetId()) ? rootDataset
                    : store.one("SELECT * FROM mg_eval_dataset WHERE id=? FOR UPDATE", input.datasetId());
            int version = (int) store.count("SELECT MAX(version) FROM mg_eval_dataset WHERE root_id=?", root) + 1;
            var samples = store.rows("SELECT * FROM mg_eval_case WHERE dataset_id=? ORDER BY ordinal_no", input.datasetId());
            Map<String, ReferenceInput> references = new LinkedHashMap<>();
            for (var reference : input.cases() == null ? List.<ReferenceInput>of() : input.cases()) {
                if (samples.stream().noneMatch(row -> row.get("id").equals(reference.caseId()))) throw invalid("参考答案包含其他评测集样本");
                if (reference.referenceText() != null && reference.referenceText().length() > maxSampleBytes) throw invalid("参考答案过长");
                difficulty(reference.difficulty());
                if (references.put(reference.caseId(), reference) != null) throw invalid("同一样本不能重复提交");
            }
            String id = EvaluationStore.id();
            store.update("INSERT INTO mg_eval_dataset(id,root_id,name,version,created_by) VALUES(?,?,?,?,?)",
                    id, root, prior.get("name"), version, operators.currentOperatorUm());
            for (var sample : samples) {
                String caseId = String.valueOf(sample.get("id"));
                var change = references.get(caseId);
                Object reference = change == null ? sample.get("referenceText") : change.referenceText();
                String level = change == null || change.difficulty() == null
                        ? String.valueOf(sample.get("difficulty")) : difficulty(change.difficulty());
                store.update("INSERT INTO mg_eval_case(id,dataset_id,payload_id,ordinal_no,snapshot_json,reference_text,difficulty,title) VALUES(?,?,?,?,?,?,?,?)",
                        EvaluationStore.id(), id, sample.get("payloadId"), sample.get("ordinalNo"), sample.get("snapshotJson"),
                        reference, level, sample.get("title"));
            }
            return id;
        });
    }

    public String importDataset(ImportInput input) {
        String datasetName = name(input.name());
        if (input.cases() == null || input.cases().isEmpty() || input.cases().size() > maxCases)
            throw invalid("请选择 1–" + maxCases + " 条样本");
        var snapshots = new ArrayList<String>();
        long totalBytes = 0;
        for (int index = 0; index < input.cases().size(); index++) {
            try {
                var item = input.cases().get(index);
                if (item == null) throw invalid("样本不能为空");
                if (item.title() != null && item.title().length() > 128) throw invalid("标题不能超过 128 字");
                difficulty(item.difficulty());
                if (item.referenceText() != null && item.referenceText().length() > maxSampleBytes) throw invalid("参考答案过长");
                if (item.request() != null && item.prompt() != null && !item.prompt().isBlank())
                    throw invalid("问题与完整请求请只填写一项");
                var request = item.request() == null ? mapper.createObjectNode() : item.request().deepCopy();
                if (item.request() == null && item.prompt() != null && !item.prompt().isBlank()) {
                    request.putArray("messages").addObject().put("role", "user").put("content", item.prompt());
                }
                var messages = request.path("messages");
                if (!messages.isArray() || messages.isEmpty()) throw invalid("请填写问题或非空 messages 数组");
                for (var message : messages) {
                    if (!message.isObject() || !Set.of("system", "developer", "user", "assistant", "tool")
                            .contains(message.path("role").asText())) throw invalid("messages 包含无效的消息角色");
                }
                var snapshot = new PayloadDetailVO();
                snapshot.setTrafficType("model");
                snapshot.setRequestMethod("POST");
                snapshot.setRequestPath("/v1/chat/completions");
                snapshot.setRequestBody(store.json(request));
                String json = store.json(snapshot);
                long bytes = json.getBytes(StandardCharsets.UTF_8).length
                        + (item.referenceText() == null ? 0 : item.referenceText().getBytes(StandardCharsets.UTF_8).length);
                if (bytes > maxSampleBytes) throw invalid("样本超过配置的大小上限");
                totalBytes += bytes;
                if (totalBytes > maxDatasetBytes) throw invalid("评测集超过配置的总大小上限");
                snapshots.add(json);
            } catch (com.pab.ficc.ibp.modelgate.server.common.BusinessException error) {
                throw invalid("第 " + (index + 1) + " 条样本：" + error.getMessage());
            }
        }
        return store.atomic(() -> {
            String id = EvaluationStore.id();
            store.update("INSERT INTO mg_eval_dataset(id,root_id,name,version,created_by) VALUES(?,?,?,1,?)",
                    id, id, datasetName, operators.currentOperatorUm());
            for (int index = 0; index < snapshots.size(); index++) {
                var item = input.cases().get(index);
                store.update("INSERT INTO mg_eval_case(id,dataset_id,payload_id,ordinal_no,snapshot_json,reference_text,difficulty,title)"
                                + " VALUES(?,?,NULL,?,?,?,?,?)", EvaluationStore.id(), id, index + 1, snapshots.get(index),
                        item.referenceText(), difficulty(item.difficulty()), item.title());
            }
            return id;
        });
    }

    private static String difficulty(String value) {
        if (value == null || value.isBlank()) return "UNRATED";
        if (!Set.of("UNRATED", "EASY", "MEDIUM", "HARD").contains(value)) throw invalid("难度须为 UNRATED、EASY、MEDIUM 或 HARD");
        return value;
    }

    private static String baselineAnswer(PayloadDetailVO snapshot) {
        return snapshot.getResponseText() == null ? snapshot.getResponseRaw() : snapshot.getResponseText();
    }

    public void deleteDataset(String id) {
        store.atomic(() -> {
            var dataset = store.one("SELECT * FROM mg_eval_dataset WHERE id=? FOR UPDATE", id);
            if (store.count("SELECT COUNT(*) FROM mg_eval_task WHERE dataset_id=?", id) > 0) throw invalid("请先删除关联评测任务");
            if (id.equals(dataset.get("rootId")) && store.count("SELECT COUNT(*) FROM mg_eval_dataset WHERE root_id=?", id) > 1)
                throw invalid("请先删除该评测集的后续版本");
            store.update("DELETE FROM mg_eval_case WHERE dataset_id=?", id);
            store.update("DELETE FROM mg_eval_dataset WHERE id=?", id);
            return null;
        });
    }

    public String createTask(TaskInput input) {
        String name = name(input.name());
        var model = models.findByModelId(input.modelId());
        if (!Boolean.TRUE.equals(model.getEnabled()) || !"chat".equals(model.getModelType())) throw invalid("请选择已启用的对话模型");
        int concurrency = input.concurrency() == null ? 2 : input.concurrency();
        int timeout = input.timeoutSeconds() == null ? 120 : input.timeoutSeconds();
        if (concurrency < 1 || concurrency > maxConcurrency) throw invalid("任务并发需在 1–" + maxConcurrency + " 之间");
        if (timeout < 1 || timeout > 3600) throw invalid("单条超时需在 1–3600 秒之间");
        ObjectNode overrides = input.overrides() == null ? mapper.createObjectNode() : input.overrides();
        if (overrides.has("model") || overrides.has("messages") || overrides.has("input"))
            throw invalid("统一参数不能替换模型身份或样本消息");
        if (store.json(overrides).length() > 65536) throw invalid("统一参数过长");
        // 适配器预检无需调用上游。
        var samples = store.rows("SELECT * FROM mg_eval_case WHERE dataset_id=? ORDER BY ordinal_no", input.datasetId());
        if (samples.isEmpty()) throw invalid("评测集不存在或没有样本");
        List<String> requests = new ArrayList<>();
        for (var sample : samples) requests.add(runner.prepare(model,
                store.read(sample.get("snapshotJson"), PayloadDetailVO.class).getRequestBody(), overrides));
        return store.atomic(() -> {
            store.one("SELECT id FROM mg_eval_dataset WHERE id=? FOR UPDATE", input.datasetId());
            String id = EvaluationStore.id();
            store.update("INSERT INTO mg_eval_task(id,name,dataset_id,target_model_id,model_snapshot,overrides_json,concurrency,timeout_seconds,status,created_by)"
                            + " VALUES(?,?,?,?,?,?,?,?,'QUEUED',?)",
                    id, name, input.datasetId(), model.getModelId(), store.json(model), store.json(overrides),
                    concurrency, timeout, operators.currentOperatorUm());
            for (int i = 0; i < samples.size(); i++) store.update(
                    "INSERT INTO mg_eval_result(id,task_id,case_id,attempt,status,request_json) VALUES(?,?,?,1,'WAITING',?)",
                    EvaluationStore.id(), id, samples.get(i).get("id"), requests.get(i));
            return id;
        });
    }

    public Map<String, Object> tasks(int page, int size) {
        return store.page("SELECT t.id,t.name,t.dataset_id,t.target_model_id,t.concurrency,t.timeout_seconds,t.status,"
                        + "t.created_by,t.create_time,t.update_time FROM mg_eval_task t ORDER BY t.create_time DESC,t.id DESC",
                "SELECT COUNT(*) FROM mg_eval_task", page, size);
    }

    public Map<String, Object> task(String id) {
        var task = store.one("SELECT * FROM mg_eval_task WHERE id=?", id);
        store.decode(task, "modelSnapshot", "overridesJson");
        task.put("counts", store.rows("SELECT r.status,COUNT(*) AS total FROM mg_eval_result r WHERE r.task_id=? AND "
                + EvaluationStore.LATEST + " GROUP BY r.status", id));
        var metrics = store.one("SELECT COUNT(*) AS total,COALESCE(SUM(r.input_tokens),0) AS input_tokens,"
                + "COALESCE(SUM(r.output_tokens),0) AS output_tokens,COALESCE(AVG(r.latency_ms),0) AS avg_latency_ms "
                + "FROM mg_eval_result r WHERE r.task_id=?", id);
        metrics.putAll(store.one("SELECT SUM(CASE WHEN r.verdict='PASS' THEN 1 ELSE 0 END) AS passed,"
                + "SUM(CASE WHEN r.verdict IN ('PASS','FAIL') THEN 1 ELSE 0 END) AS reviewed "
                + "FROM mg_eval_result r WHERE r.task_id=? AND " + EvaluationStore.LATEST, id));
        task.put("metrics", metrics);
        return task;
    }

    public Map<String, Object> results(String taskId, String status, String verdict, int page, int size) {
        store.one("SELECT id FROM mg_eval_task WHERE id=?", taskId);
        String where = " FROM mg_eval_result r JOIN mg_eval_case c ON c.id=r.case_id WHERE r.task_id=? AND "
                + EvaluationStore.LATEST + " AND (?='' OR r.status=?) AND (?='' OR r.verdict=?)";
        return store.page("SELECT r.id,r.case_id,c.payload_id,c.ordinal_no,c.difficulty,c.title,r.attempt,r.status,r.verdict,r.score,r.note,"
                        + "r.started_time,r.finished_time,r.http_status,r.latency_ms,r.input_tokens,r.output_tokens,r.token_source,r.response_complete"
                        + where + " ORDER BY c.ordinal_no",
                "SELECT COUNT(*)" + where, page, size, taskId, status, status, verdict, verdict);
    }

    public Map<String, Object> result(String id) {
        var result = store.one("SELECT * FROM mg_eval_result WHERE id=?", id);
        store.decode(result, "responseJson");
        result.put("sample", caseDetail(String.valueOf(result.get("caseId"))));
        result.put("history", store.rows("SELECT id,attempt,status,started_time,finished_time FROM mg_eval_result WHERE task_id=? AND case_id=? ORDER BY attempt",
                result.get("taskId"), result.get("caseId")));
        return result;
    }

    public void cancel(String id) {
        store.atomic(() -> {
            var task = store.lockTask(id);
            if (!List.of("QUEUED", "RUNNING").contains(task.get("status"))) throw invalid("任务已结束");
            LocalDateTime now = LocalDateTime.now();
            store.update("UPDATE mg_eval_task SET status='CANCELLED',update_time=? WHERE id=?", now, id);
            store.update("UPDATE mg_eval_result SET status='CANCELLED',finished_time=?,update_time=? WHERE task_id=? AND status IN ('WAITING','RUNNING')", now, now, id);
            return null;
        });
    }

    public void retry(RetryInput input) {
        store.atomic(() -> {
            var task = store.lockTask(input.taskId());
            if (List.of("QUEUED", "RUNNING").contains(task.get("status"))) throw invalid("请等待任务结束后重跑");
            var rows = store.rows("SELECT r.* FROM mg_eval_result r WHERE r.task_id=? AND " + EvaluationStore.LATEST, input.taskId());
            Set<String> selected = input.resultIds() == null ? Set.of() : new HashSet<>(input.resultIds());
            if (selected.isEmpty() || rows.stream().filter(row -> selected.contains(row.get("id"))).count() != selected.size())
                throw invalid("请选择当前任务需要重跑的结果");
            for (var row : rows) if (selected.contains(row.get("id"))) {
                store.update("INSERT INTO mg_eval_result(id,task_id,case_id,attempt,status,request_json) VALUES(?,?,?,?,'WAITING',?)",
                        EvaluationStore.id(), input.taskId(), row.get("caseId"), ((Number) row.get("attempt")).intValue() + 1, row.get("requestJson"));
            }
            store.update("UPDATE mg_eval_task SET status='QUEUED',update_time=? WHERE id=?", LocalDateTime.now(), input.taskId());
            return null;
        });
    }

    public void score(ScoreInput input) {
        if (!Set.of("PENDING", "PASS", "FAIL").contains(String.valueOf(input.verdict()))
                || input.score() != null && (input.score() < 0 || input.score() > 100)
                || input.note() != null && input.note().length() > 10000) throw invalid("评分需为 0–100，评语不超过 10000 字");
        var row = store.one("SELECT status FROM mg_eval_result WHERE id=?", input.resultId());
        if (List.of("WAITING", "RUNNING").contains(row.get("status"))) throw invalid("执行完成后才能评分");
        store.update("UPDATE mg_eval_result SET verdict=?,score=?,note=?,update_time=? WHERE id=?",
                input.verdict(), input.score(), input.note(), LocalDateTime.now(), input.resultId());
    }

    public void deleteTask(String id) {
        store.atomic(() -> {
            var task = store.lockTask(id);
            if (List.of("QUEUED", "RUNNING").contains(task.get("status"))) throw invalid("请先结束任务");
            store.update("DELETE FROM mg_eval_result WHERE task_id=?", id);
            store.update("DELETE FROM mg_eval_task WHERE id=?", id);
            return null;
        });
    }

    private PayloadDetailVO snapshot(Long id, boolean personal) {
        if (id == null) throw invalid("报文编号不能为空");
        var entity = payloads.selectById(id);
        if (entity == null) throw invalid("报文已清理: " + id);
        var people = entity.getAppId() == null ? Map.<String, com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity>of()
                : scopes.personalUsersByAppId(List.of(entity.getAppId()));
        if (personal != !people.isEmpty()) throw invalid("报文不属于选定接入范围: " + id);
        var sample = payloads.selectScopedDetail(id, personal ? entity.getAppId() : null);
        if (sample == null || !"model".equals(sample.getTrafficType())) throw invalid("仅支持模型报文: " + id);
        if (sample.getRequestPath() != null && !sample.getRequestPath().split("\\?", 2)[0].endsWith("/v1/chat/completions"))
            throw invalid("首期支持 Chat Completions 报文: " + id);
        if (sample.getRequestBody() == null || sample.getRequestBody().isBlank()) throw invalid("报文未留存请求体: " + id);
        var body = store.read(sample.getRequestBody(), ObjectNode.class);
        if (body == null || !body.path("messages").isArray()) throw invalid("样本缺少 messages: " + id);
        if (personal) {
            var person = people.get(entity.getAppId());
            sample.setUm(person.getPersonUm());
            sample.setPersonName(person.getAppName());
            sample.setAppId(null);
        }
        sample.setRequestHeaders(null);
        if (store.json(sample).getBytes(StandardCharsets.UTF_8).length > maxSampleBytes)
            throw invalid("样本超过配置的快照大小上限: " + id);
        return sample;
    }

    private void checkSelection(DatasetInput input) {
        if (input.payloadIds() == null || input.payloadIds().isEmpty() || input.payloadIds().size() > maxCases)
            throw invalid("请选择 1–" + maxCases + " 条模型报文");
        if (new HashSet<>(input.payloadIds()).size() != input.payloadIds().size()) throw invalid("报文编号不能重复");
    }

    private static String name(String name) {
        if (name == null || name.isBlank() || name.strip().length() > 128) throw invalid("名称需为 1–128 字");
        return name.strip();
    }
}
