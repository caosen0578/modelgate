package com.pab.ficc.ibp.modelgate.server.service.evaluation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.sql.Clob;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/** 所有领取、取消和重试操作在同一任务行锁下串行化。 */
@Repository
public class EvaluationStore {
    public static final String LATEST = "NOT EXISTS (SELECT 1 FROM mg_eval_result newer WHERE newer.task_id=r.task_id "
            + "AND newer.case_id=r.case_id AND newer.attempt>r.attempt)";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper mapper;

    public EvaluationStore(@Qualifier("tdsqlDataSource") DataSource source,
            @Qualifier("tdsqlTransactionManager") PlatformTransactionManager manager, ObjectMapper mapper) {
        jdbc = new JdbcTemplate(source);
        jdbc.setQueryTimeout(30);
        transaction = new TransactionTemplate(manager);
        this.mapper = mapper;
    }

    public <T> T atomic(Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }

    public int update(String sql, Object... args) {
        return jdbc.update(sql, args);
    }

    public long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0 : value;
    }

    public List<Map<String, Object>> rows(String sql, Object... args) {
        return jdbc.query(sql, (rs, index) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            for (int i = 1; i <= rs.getMetaData().getColumnCount(); i++) {
                String name = rs.getMetaData().getColumnLabel(i).toLowerCase(java.util.Locale.ROOT);
                String[] parts = name.split("_");
                var key = new StringBuilder(parts[0]);
                for (int j = 1; j < parts.length; j++) {
                    key.append(Character.toUpperCase(parts[j].charAt(0))).append(parts[j].substring(1));
                }
                Object value = rs.getObject(i);
                if (value instanceof Timestamp timestamp) value = timestamp.toLocalDateTime();
                if (value instanceof Clob clob) value = clob.getSubString(1, Math.toIntExact(clob.length()));
                row.put(key.toString(), value);
            }
            return row;
        }, args);
    }

    public Map<String, Object> one(String sql, Object... args) {
        var rows = rows(sql, args);
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "评测记录不存在");
        return rows.get(0);
    }

    public String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("评测数据无法序列化", e); }
    }

    public <T> T read(Object value, Class<T> type) {
        try { return mapper.readValue(String.valueOf(value), type); }
        catch (JsonProcessingException e) { throw new IllegalArgumentException("评测数据格式异常", e); }
    }

    public void decode(Map<String, Object> row, String... fields) {
        for (String field : fields) if (row.get(field) != null) row.put(field, read(row.get(field), Object.class));
    }

    public Map<String, Object> page(String select, String countSql, int page, int size, Object... args) {
        if (page < 1 || size < 1 || size > 200) throw invalid("分页参数不正确");
        long offset = (long) (page - 1) * size;
        List<Object> parameters = new ArrayList<>(List.of(args));
        parameters.add(size);
        parameters.add(offset);
        return Map.of("items", rows(select + " LIMIT ? OFFSET ?", parameters.toArray()),
                "total", count(countSql, args), "pageIndex", page, "pageSize", size);
    }

    public Map<String, Object> lockTask(String id) {
        return one("SELECT * FROM mg_eval_task WHERE id=? FOR UPDATE", id);
    }

    public Map<String, Object> claim(String taskId, String owner, int leaseSeconds) {
        return atomic(() -> {
            var task = lockTask(taskId);
            if (!List.of("QUEUED", "RUNNING").contains(task.get("status"))) return null;
            long running = count("SELECT COUNT(*) FROM mg_eval_result WHERE task_id=? AND status='RUNNING'", taskId);
            if (running >= ((Number) task.get("concurrency")).intValue()) return null;
            var waiting = rows("SELECT r.* FROM mg_eval_result r WHERE r.task_id=? AND r.status='WAITING' AND "
                    + LATEST + " ORDER BY r.id LIMIT 1", taskId);
            if (waiting.isEmpty()) {
                settle(taskId);
                return null;
            }
            var result = waiting.get(0);
            LocalDateTime now = LocalDateTime.now();
            update("UPDATE mg_eval_result SET status='RUNNING',lease_owner=?,lease_until=?,started_time=?,update_time=? WHERE id=? AND status='WAITING'",
                    owner, now.plusSeconds(leaseSeconds), now, now, result.get("id"));
            update("UPDATE mg_eval_task SET status='RUNNING',update_time=? WHERE id=?", now, taskId);
            result.put("task", task);
            result.put("sample", one("SELECT * FROM mg_eval_case WHERE id=?", result.get("caseId")));
            return result;
        });
    }

    public boolean heartbeat(String id, String owner, int seconds) {
        return update("UPDATE mg_eval_result SET lease_until=?,update_time=? WHERE id=? AND lease_owner=? AND status='RUNNING'",
                LocalDateTime.now().plusSeconds(seconds), LocalDateTime.now(), id, owner) == 1;
    }

    public boolean saveRequest(String id, String owner, String body) {
        return update("UPDATE mg_eval_result SET request_json=?,update_time=? WHERE id=? AND lease_owner=? AND status='RUNNING'",
                body, LocalDateTime.now(), id, owner) == 1;
    }

    public void finish(String taskId, String id, String owner, String status, EvaluationRunner.Outcome response) {
        atomic(() -> {
            lockTask(taskId);
            update("UPDATE mg_eval_result SET status=?,response_json=?,finished_time=?,lease_until=NULL,update_time=?,"
                            + "http_status=?,latency_ms=?,input_tokens=?,output_tokens=?,token_source=?,response_complete=? "
                            + "WHERE id=? AND lease_owner=? AND status='RUNNING'",
                    status, json(response), LocalDateTime.now(), LocalDateTime.now(), response.httpStatus(), response.latencyMs(),
                    response.inputTokens(), response.outputTokens(), response.tokenSource(), response.complete(), id, owner);
            settle(taskId);
            return null;
        });
    }

    public void recover() {
        var now = LocalDateTime.now();
        var expired = rows("SELECT DISTINCT task_id FROM mg_eval_result WHERE status='RUNNING' AND lease_until<?", now);
        for (var row : expired) atomic(() -> {
            String taskId = String.valueOf(row.get("taskId"));
            lockTask(taskId);
            update("UPDATE mg_eval_result SET status='UNCERTAIN',finished_time=?,update_time=? WHERE task_id=? "
                    + "AND status='RUNNING' AND lease_until<?", now, now, taskId, now);
            settle(taskId);
            return null;
        });
    }

    public void settle(String taskId) {
        long pending = count("SELECT COUNT(*) FROM mg_eval_result r WHERE r.task_id=? AND " + LATEST
                + " AND r.status IN ('WAITING','RUNNING')", taskId);
        if (pending > 0) return;
        long failed = count("SELECT COUNT(*) FROM mg_eval_result r WHERE r.task_id=? AND " + LATEST
                + " AND r.status<>'SUCCESS'", taskId);
        update("UPDATE mg_eval_task SET status=?,update_time=? WHERE id=? AND status IN ('QUEUED','RUNNING')",
                failed == 0 ? "COMPLETED" : "PARTIAL", LocalDateTime.now(), taskId);
    }

    public static String id() { return UUID.randomUUID().toString().replace("-", ""); }
    public static BusinessException invalid(String message) { return new BusinessException(ErrorCode.INVALID_PARAMETER, message); }
}
