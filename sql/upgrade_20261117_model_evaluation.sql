CREATE TABLE IF NOT EXISTS mg_eval_dataset (
    id VARCHAR(32) NOT NULL,
    root_id VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    version INT NOT NULL,
    created_by VARCHAR(100) DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_eval_dataset_version (root_id, version)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='固定版本的模型评测集';

CREATE TABLE IF NOT EXISTS mg_eval_case (
    id VARCHAR(32) NOT NULL,
    dataset_id VARCHAR(32) NOT NULL,
    payload_id BIGINT NOT NULL,
    ordinal_no INT NOT NULL,
    snapshot_json LONGTEXT NOT NULL,
    reference_text LONGTEXT,
    PRIMARY KEY (id),
    UNIQUE KEY uk_eval_case_payload (dataset_id, payload_id),
    KEY idx_eval_case_dataset (dataset_id, ordinal_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评测请求与历史响应快照';

CREATE TABLE IF NOT EXISTS mg_eval_task (
    id VARCHAR(32) NOT NULL,
    name VARCHAR(128) NOT NULL,
    dataset_id VARCHAR(32) NOT NULL,
    target_model_id VARCHAR(64) NOT NULL,
    model_snapshot LONGTEXT NOT NULL,
    overrides_json LONGTEXT NOT NULL,
    concurrency INT NOT NULL,
    timeout_seconds INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    created_by VARCHAR(100) DEFAULT NULL,
    create_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_eval_task_status (status, create_time),
    KEY idx_eval_task_dataset (dataset_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='持久化模型评测任务';

CREATE TABLE IF NOT EXISTS mg_eval_result (
    id VARCHAR(32) NOT NULL,
    task_id VARCHAR(32) NOT NULL,
    case_id VARCHAR(32) NOT NULL,
    attempt INT NOT NULL,
    status VARCHAR(24) NOT NULL,
    lease_owner VARCHAR(64) DEFAULT NULL,
    lease_until DATETIME DEFAULT NULL,
    started_time DATETIME DEFAULT NULL,
    finished_time DATETIME DEFAULT NULL,
    request_json LONGTEXT,
    response_json LONGTEXT,
    http_status INT DEFAULT NULL,
    latency_ms BIGINT DEFAULT NULL,
    input_tokens INT DEFAULT NULL,
    output_tokens INT DEFAULT NULL,
    token_source VARCHAR(16) DEFAULT NULL,
    response_complete TINYINT(1) DEFAULT NULL,
    verdict VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    score INT DEFAULT NULL,
    note TEXT,
    update_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_eval_result_attempt (task_id, case_id, attempt),
    KEY idx_eval_result_task (task_id, status),
    KEY idx_eval_result_lease (status, lease_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='评测每次执行结果及人工评分';
