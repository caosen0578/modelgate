-- 在网关库分批执行。改时间范围和接入应用后运行，先核对应用确属 CodeBuddy。
SET @app_id = '95731';
SET @from_time = CURRENT_DATE - INTERVAL 7 DAY;
SET @to_time = NOW();

UPDATE mg_audit_log a
JOIN (
    SELECT MIN(id) AS audit_id, model_id, trace_id
    FROM mg_audit_log
    WHERE app_id = @app_id AND traffic_type = 'model' AND model_id IS NOT NULL
    GROUP BY model_id, trace_id
    HAVING COUNT(*) = 1
) ua ON ua.audit_id = a.id
JOIN (
    SELECT MIN(id) AS payload_id, model_id, trace_id
    FROM mg_request_payload
    WHERE app_id = @app_id AND traffic_type = 'model' AND model_id IS NOT NULL
    GROUP BY model_id, trace_id
    HAVING COUNT(*) = 1
) up ON up.trace_id = ua.trace_id AND up.model_id = ua.model_id
JOIN (
    SELECT id, create_time, LOWER(REPLACE(TRIM(COALESCE(
        JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(request_headers), request_headers, '{}'), '$."x-codebuddy-user-id"')),
        JSON_UNQUOTE(JSON_EXTRACT(IF(JSON_VALID(request_headers), request_headers, '{}'), '$."X-CodeBuddy-User-Id"'))
    )), '‑', '-')) AS codebuddy_uuid
    FROM mg_request_payload
    WHERE app_id = @app_id
) p ON p.id = up.payload_id
SET a.codebuddy_user_id = p.codebuddy_uuid
WHERE a.codebuddy_user_id IS NULL
  AND a.create_time >= @from_time AND a.create_time < @to_time
  AND ABS(TIMESTAMPDIFF(SECOND, a.create_time, p.create_time)) <= 5
  AND p.codebuddy_uuid REGEXP '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$';
