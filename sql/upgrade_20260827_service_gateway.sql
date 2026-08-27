-- ============================================================================
-- ModelGate 增量脚本 · 2026-08-27 · 通用服务接入（/svc/** 流量）
-- ----------------------------------------------------------------------------
-- 设计依据：spec/modelgate/12-通用服务接入设计.md
--
-- 本脚本做四件事：
--   1. 新建 mg_service            —— 服务注册（/svc/{service_id}/** 的上游目标）
--   2. 新建 mg_service_credential —— 服务上游鉴权凭证（与 mg_service 一对一）
--   3. 新建 mg_app_service_auth   —— 接入系统 × 服务 授权
--   4. 给 mg_audit_log / mg_request_payload 各加 traffic_type + service_id 两列
--
-- ⚠️ 第 4 步作用在**高频追加表**上，可能已有大量数据，执行前务必先看行数：
--       SELECT COUNT(*) FROM mg_audit_log;
--       SELECT COUNT(*) FROM mg_request_payload;
--     MySQL 8 的 ADD COLUMN 多为 INSTANT（秒级），但 ADD KEY 是 INPLACE、需要时间且占 IO。
--     行数上千万时建议安排在低峰，或走 pt-online-schema-change / gh-ost。
--
-- ⚠️ 本脚本**不含 DROP**，可重复执行（建表用 IF NOT EXISTS；加列用存储过程做存在性判断，
--     避免二次执行报 ER_DUP_FIELDNAME 1060 中断）。
--
-- 执行方式：
--   mysql -h<host> -P<port> -u<user> -p <库名> < sql/upgrade_20260827_service_gateway.sql
-- ============================================================================

-- ============================================================================
-- 1. mg_service —— 通用服务注册
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_service (
    id             BIGINT       NOT NULL AUTO_INCREMENT           COMMENT '主键，自增',
    -- 对外路径就是 /svc/{service_id}/**，因此它是 URL 的一部分：一经创建不可修改
    -- （改名等于换 URL，接入方必须同步改代码，那是"新建+下线"而不是"改名"）。
    -- 与 mg_model.model_id 的差别正在于此，后者是协议字段、支持 ON UPDATE CASCADE 改名。
    service_id     VARCHAR(64)  NOT NULL                          COMMENT '服务编码（业务唯一键），对外路径 /svc/{service_id}/**；格式 ^[a-z0-9][a-z0-9-]{1,31}$，创建后不可修改',
    service_name   VARCHAR(128) NOT NULL                          COMMENT '服务展示名称',
    owner_system   VARCHAR(128)                                   COMMENT '所属业务系统中文名（与 mg_app.system_name 同口径，仅展示）',
    upstream_url   VARCHAR(512) NOT NULL                          COMMENT '上游地址（协议+域名+端口[+固定前缀路径]，末尾斜杠由代码规范化去掉）',
    strip_prefix   TINYINT(1)   NOT NULL DEFAULT 1                COMMENT '是否剥掉 /svc/{service_id} 再转发：1=剥掉（默认）0=完整路径原样转发',
    -- 每服务独立超时，通过 Route metadata 的 response-timeout 下发给 NettyRoutingFilter。
    -- 注意该值的真实语义是「等上游吐出响应头的最长时间」，管不到响应体（SSE 帧不受它约束）——
    -- 与 mg_model.timeout_ms 只用于连通性测试不同，本字段是真正生效的。详见 12 §11.1。
    timeout_ms     INT          NOT NULL DEFAULT 0                COMMENT '单请求超时（毫秒），0=沿用全局 httpclient.response-timeout',
    sse            TINYINT(1)   NOT NULL DEFAULT 0                COMMENT '是否 SSE 服务：1=是（影响管理面展示与熔断配置选择，不影响转发本身）',
    -- 报文开关放在服务本身而非授权行：模型侧放授权行是因为不同接入系统合规要求不同；
    -- 服务侧的诉求是「每个接口录入时决定记不记」，粒度在接口本身，放这里更贴合且少一次查询。
    payload_log_enabled TINYINT(1) NOT NULL DEFAULT 0             COMMENT '请求/响应报文记录开关：1=记录到 mg_request_payload，0=不记录（默认关闭）',
    description    VARCHAR(512)                                   COMMENT '服务描述/备注',
    enabled        TINYINT(1)   NOT NULL DEFAULT 1                COMMENT '是否启用：1=启用 0=禁用（禁用后请求该服务返回 503）',
    creater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP         COMMENT '创建时间',
    updater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del         VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志：1=已删除 0=未删除（逻辑删除）',
    creater_um     VARCHAR(100) DEFAULT NULL                      COMMENT '创建人 UM 账号',
    updater_um     VARCHAR(100) DEFAULT NULL                      COMMENT '更新人 UM 账号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_id (service_id, is_del)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='通用服务注册（/svc/** 流量的上游目标）';

-- ============================================================================
-- 2. mg_service_credential —— 服务上游鉴权凭证（与 mg_service 一对一）
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_service_credential (
    id            BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键，自增',
    service_id    VARCHAR(64)  NOT NULL                          COMMENT '关联 mg_service.service_id（一对一）',
    auth_type     VARCHAR(32)  NOT NULL DEFAULT 'none'           COMMENT '上游鉴权类型：none=不注入 | api_key=Authorization Bearer | ak_sk=X-Access-Key/X-Secret-Key | custom_header=仅注入 extra_headers',
    access_key    VARCHAR(256)                                   COMMENT 'AK（明文）；auth_type=custom_header/none 时不使用',
    secret_key    VARCHAR(512)                                   COMMENT 'SK/密钥，加密存储（AES-256-GCM，CryptoService）',
    -- 上游要求的固定自定义头，如 {"X-Tenant":"ficc","X-Channel":"gateway"}。
    -- 整体加密：这些头里经常混着签名密钥、租户令牌一类的敏感值，不能明文落库。
    extra_headers TEXT                                           COMMENT '附加上游请求头（JSON 对象，加密存储）',
    creater       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP         COMMENT '创建时间',
    updater       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志：1=已删除 0=未删除（逻辑删除）',
    creater_um    VARCHAR(100) DEFAULT NULL                      COMMENT '创建人 UM 账号',
    updater_um    VARCHAR(100) DEFAULT NULL                      COMMENT '更新人 UM 账号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_service_id (service_id, is_del),
    CONSTRAINT fk_svccred_service FOREIGN KEY (service_id) REFERENCES mg_service (service_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='服务上游鉴权凭证';

-- ============================================================================
-- 3. mg_app_service_auth —— 接入系统对通用服务的调用授权
--    唯一键含 is_del：软删行不占用 (app_id, service_id)，支持撤销后重新授权
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_app_service_auth (
    id             BIGINT      NOT NULL AUTO_INCREMENT            COMMENT '主键，自增',
    app_id         VARCHAR(64) NOT NULL                          COMMENT '关联 mg_app.app_id',
    service_id     VARCHAR(64) NOT NULL                          COMMENT '关联 mg_service.service_id',
    enabled        TINYINT(1)  NOT NULL DEFAULT 1                COMMENT '是否启用：1=启用 0=禁用（禁用后该系统访问该服务返回 403）',
    qps_limit      INT         NOT NULL DEFAULT 0                COMMENT '每秒请求数上限，0=不限',
    daily_limit    INT         NOT NULL DEFAULT 0                COMMENT '每日请求数上限，0=不限',
    -- 刻意**没有** monthly_tokens：服务不产生 Token，该维度对它无意义。
    -- RateLimitService.checkAllQuotas 的月度参数传 0，方法内 ≤0 直接跳过、不发 Redis 命令。
    expires_at     DATETIME                                      COMMENT '授权过期时间，NULL=永久授权（过期等同禁用）',
    creater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP        COMMENT '创建时间',
    updater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del         VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志：1=已删除 0=未删除（逻辑删除）',
    creater_um     VARCHAR(100) DEFAULT NULL                     COMMENT '创建人 UM 账号',
    updater_um     VARCHAR(100) DEFAULT NULL                     COMMENT '更新人 UM 账号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_service (app_id, service_id, is_del),
    KEY idx_service_id (service_id),
    CONSTRAINT fk_svcauth_app     FOREIGN KEY (app_id)     REFERENCES mg_app (app_id),
    CONSTRAINT fk_svcauth_service FOREIGN KEY (service_id) REFERENCES mg_service (service_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接入系统对通用服务的调用授权';

-- ============================================================================
-- 4. 审计与报文表加列（幂等：已存在则跳过）
-- ----------------------------------------------------------------------------
-- 为什么复用现表而不新建：审计/报文那一整套缓冲队列、批量刷盘、失败重试、退出刷盘、
--   保留期清理机制都在这两张表上，新建表要把它们再造一遍。
-- 为什么不复用 model_id 列装 service_id：现有用量统计 SQL 按 model_id 分组，
--   塞进去会让服务流量混进模型用量报表。加独立列 + traffic_type 后，
--   现有统计查询只需补一个 WHERE traffic_type = 'model'，默认值保证历史数据自动归位。
-- ============================================================================

DROP PROCEDURE IF EXISTS mg_add_column_if_absent;
DELIMITER $$
CREATE PROCEDURE mg_add_column_if_absent(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_ddl    TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME   = p_table
                     AND COLUMN_NAME  = p_column) THEN
        SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$

CREATE PROCEDURE mg_add_index_if_absent(
    IN p_table VARCHAR(64),
    IN p_index VARCHAR(64),
    IN p_ddl   TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.STATISTICS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME   = p_table
                     AND INDEX_NAME   = p_index) THEN
        SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD KEY ', p_ddl);
        PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- mg_audit_log
CALL mg_add_column_if_absent('mg_audit_log', 'traffic_type',
    "traffic_type VARCHAR(16) NOT NULL DEFAULT 'model' COMMENT '流量类型：model=模型流量 service=通用服务流量' AFTER trace_id");
CALL mg_add_column_if_absent('mg_audit_log', 'service_id',
    "service_id VARCHAR(64) DEFAULT NULL COMMENT '服务编码（traffic_type=service 时写入，model 流量为 NULL）' AFTER model_id");
CALL mg_add_index_if_absent('mg_audit_log', 'idx_service_id', 'idx_service_id (service_id)');

-- mg_request_payload
CALL mg_add_column_if_absent('mg_request_payload', 'traffic_type',
    "traffic_type VARCHAR(16) NOT NULL DEFAULT 'model' COMMENT '流量类型：model=模型流量 service=通用服务流量' AFTER trace_id");
CALL mg_add_column_if_absent('mg_request_payload', 'service_id',
    "service_id VARCHAR(64) DEFAULT NULL COMMENT '服务编码（traffic_type=service 时写入，model 流量为 NULL）' AFTER model_id");
CALL mg_add_index_if_absent('mg_request_payload', 'idx_service_time', 'idx_service_time (service_id, create_time)');

DROP PROCEDURE IF EXISTS mg_add_column_if_absent;
DROP PROCEDURE IF EXISTS mg_add_index_if_absent;

-- ============================================================================
-- 5. 样例数据（默认注释掉，勿在生产库执行）
-- ----------------------------------------------------------------------------
-- 开发联调时可取消注释。service_id 取值需符合 ^[a-z0-9][a-z0-9-]{1,31}$。
-- ============================================================================
-- INSERT INTO mg_service (service_id, service_name, owner_system, upstream_url, sse, description) VALUES
--   ('harness',   '对话系统 harness 后端', '对话平台', 'http://harness.intranet:8080',   1, 'SSE 长流，主入口'),
--   ('agent',     'Agent 编排服务',        '对话平台', 'http://agent.intranet:8000',     1, 'SSE 长流，多步工具调用'),
--   ('retrieval', '向量检索服务',          '对话平台', 'http://retrieval.intranet:8100', 0, '短请求'),
--   ('kb',        '知识库服务',            '知识中心', 'http://kb.intranet:9000/gateway',0, '文档/切片查询'),
--   ('session',   '会话与历史管理',        '对话平台', 'http://session.intranet:8200',   0, 'CRUD');
-- INSERT INTO mg_service_credential (service_id, auth_type) VALUES
--   ('harness','none'),('agent','none'),('retrieval','none'),('kb','none'),('session','none');
