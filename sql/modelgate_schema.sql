-- ============================================================================
-- ModelGate · 完整建表脚本（全新库一次性执行）
-- ----------------------------------------------------------------------------
-- 字符集：utf8mb4          排序规则：utf8mb4_unicode_ci（字符串默认）
-- 引擎：InnoDB             时区：Asia/Shanghai
-- ----------------------------------------------------------------------------
-- 执行方式：在目标数据库下直接执行（脚本不含 CREATE DATABASE / USE，
--          请先 `USE 你的库名;` 或用 `mysql -h.. -P.. -u.. -p 库名 < modelgate_schema.sql`）。
--          例：mysql -h47.119.189.29 -P65522 -uroot -p cbdb < sql/modelgate_schema.sql
-- ----------------------------------------------------------------------------
-- 内部规范基础字段：
--   creater      创建人           create_time  创建时间
--   updater      修改人           update_time  修改时间
--   is_del       删除标志(逻辑删除，'0'=未删 '1'=已删，@TableLogic 驱动)
--   creater_um   创建人 UM        updater_um   更新人 UM
--   → 由 AuditFieldMetaObjectHandler 自动填充；creater/updater/um 当前占位为 NULL。
-- 两处例外（有意为之，勿"补齐"）：
--   mg_audit_log       高频追加表，不含任何基础字段，按 create_time 物理清理
--   mg_gateway_config  含其余基础字段但**无 is_del**：config_key 即主键，软删后同 key
--                      无法重建（详见该表上方说明），改为物理删除
-- 建表顺序遵循外键依赖：先父表（mg_model / mg_app）后子表。
-- ============================================================================

-- ============================================================================
-- 1. mg_model —— AI 模型实例
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_model (
    id             BIGINT       NOT NULL AUTO_INCREMENT           COMMENT '主键，自增',
    model_id       VARCHAR(64)  NOT NULL                          COMMENT '对外暴露的模型标识（业务唯一键），如 gpt-4o',
    -- 留空 = 直接透传 model_id；填了 = 转发前把请求体里的 model 换成它。
    -- 用途：同一真实模型部署在多个平台时，平台会以「模型 ID 重复」拒绝新增第二个地址，
    -- 于是对外换个名字、转发前再改回真实 ID。调用方无感知，审计与配额仍按对外 ID 统计。
    upstream_model_id VARCHAR(64)                                 COMMENT '上游真实模型 ID，留空则透传 model_id',
    model_name     VARCHAR(128) NOT NULL                          COMMENT '模型展示名称',
    provider       VARCHAR(32)  NOT NULL                          COMMENT '提供商：openai|azure_openai|anthropic|private|custom',
    model_type     VARCHAR(32)  NOT NULL DEFAULT 'chat'           COMMENT '模型类型：chat|embedding|image|audio',
    api_spec       VARCHAR(32)  NOT NULL DEFAULT 'openai'         COMMENT 'API 规范（参数转换规则）：openai|azure_openai|anthropic|custom',
    upstream_url   VARCHAR(512) NOT NULL                          COMMENT '上游模型服务请求地址（协议+域名+端口，不含接口路径）',
    upstream_path  VARCHAR(256)                                   COMMENT '上游接口路径，如 /v1/chat/completions；留空则按 api_spec 取默认值（openai/custom 透传调用方路径，anthropic 用 /v1/messages，azure 忽略本字段）',
    timeout_ms     INT          NOT NULL DEFAULT 30000            COMMENT '上游请求超时时间（毫秒）',
    default_params TEXT                                           COMMENT '默认参数（JSON），业务未传时注入，可被业务覆盖',
    force_params   TEXT                                           COMMENT '强制参数（JSON），强制注入，业务不可覆盖',
    param_mapping  TEXT                                           COMMENT '自定义字段映射规则（JSON），仅 api_spec=custom 时使用',
    description    VARCHAR(512)                                   COMMENT '模型描述/备注',
    enabled        TINYINT(1)   NOT NULL DEFAULT 1                COMMENT '是否启用：1=启用 0=禁用（禁用后请求该模型返回 503）',
    creater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP         COMMENT '创建时间',
    updater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del         VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志：1=已删除 0=未删除（逻辑删除）',
    creater_um     VARCHAR(100) DEFAULT NULL                      COMMENT '创建人 UM 账号',
    updater_um     VARCHAR(100) DEFAULT NULL                      COMMENT '更新人 UM 账号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_id (model_id, is_del)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI 模型实例';

-- ============================================================================
-- 2. mg_model_credential —— 模型上游鉴权凭证（与 mg_model 一对一）
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_model_credential (
    id            BIGINT       NOT NULL AUTO_INCREMENT            COMMENT '主键，自增',
    model_id      VARCHAR(64)  NOT NULL                          COMMENT '关联 mg_model.model_id（一对一）',
    auth_type     VARCHAR(32)  NOT NULL                          COMMENT '上游鉴权类型：api_key|ak_sk|azure|none',
    access_key    VARCHAR(256)                                   COMMENT 'AK 或 Azure resource name（明文）',
    secret_key    VARCHAR(512)                                   COMMENT 'SK/密钥，加密存储（AES-256-GCM）',
    extra_config  TEXT                                           COMMENT '额外配置（JSON），如 Azure deployment_id/api_version',
    creater       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP         COMMENT '创建时间',
    updater       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志：1=已删除 0=未删除（逻辑删除）',
    creater_um    VARCHAR(100) DEFAULT NULL                      COMMENT '创建人 UM 账号',
    updater_um    VARCHAR(100) DEFAULT NULL                      COMMENT '更新人 UM 账号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_id (model_id, is_del),
    -- model_id 允许在管理页改名；凭证业务键必须随父记录同步变化。
    CONSTRAINT fk_modelcred_model FOREIGN KEY (model_id) REFERENCES mg_model (model_id)
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型上游鉴权凭证';

-- ============================================================================
-- 3. mg_app —— 接入系统（调用方应用）
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_app (
    id           BIGINT       NOT NULL AUTO_INCREMENT             COMMENT '主键，自增',
    app_id       VARCHAR(64)  NOT NULL                           COMMENT '应用唯一标识（业务唯一键）',
    system_name  VARCHAR(128) NOT NULL                           COMMENT '所属业务系统中文名称',
    app_name     VARCHAR(128) NOT NULL                           COMMENT '应用名称',
    sys_code     VARCHAR(64)  NOT NULL                           COMMENT '所属系统编码，如 CRM、BI',
    app_code     VARCHAR(64)  NOT NULL                           COMMENT '应用编码（系统内唯一）',
    owner        VARCHAR(64)  NOT NULL                           COMMENT '负责人',
    remark       VARCHAR(512)                                    COMMENT '备注',
    enabled      TINYINT(1)   NOT NULL DEFAULT 1                 COMMENT '是否启用：1=启用 0=禁用（禁用后该系统所有凭证立即失效）',
    creater      VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    updater      VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志：1=已删除 0=未删除（逻辑删除）',
    creater_um   VARCHAR(100) DEFAULT NULL                       COMMENT '创建人 UM 账号',
    updater_um   VARCHAR(100) DEFAULT NULL                       COMMENT '更新人 UM 账号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_id (app_id, is_del)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接入系统（调用方应用）';

-- ============================================================================
-- 4. mg_app_credential —— 接入系统调用凭证
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_app_credential (
    id               BIGINT       NOT NULL AUTO_INCREMENT         COMMENT '主键，自增',
    app_id           VARCHAR(64)  NOT NULL                       COMMENT '关联 mg_app.app_id',
    cred_name        VARCHAR(128) NOT NULL                       COMMENT '凭证名称',
    cred_type        VARCHAR(32)  NOT NULL                       COMMENT '凭证类型：api_key|ak_sk',
    access_key       VARCHAR(128)                                COMMENT 'AK 明文 / API Key 末 4 位（仅展示用）',
    secret_hash      VARCHAR(128)                                COMMENT 'SHA-256(api_key) 或 SHA-256(sk)，用于鉴权比对',
    secret_encrypted VARCHAR(512)                                COMMENT '加密后的 SK（AES-256-GCM），仅 ak_sk 类型使用',
    enabled          TINYINT(1)   NOT NULL DEFAULT 1             COMMENT '是否启用：1=启用 0=禁用',
    expires_at       DATETIME                                    COMMENT '过期时间，NULL=永久有效（过期后返回 403）',
    remark           VARCHAR(512)                                COMMENT '备注',
    creater          VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time      DATETIME     DEFAULT CURRENT_TIMESTAMP      COMMENT '创建时间',
    updater          VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time      DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del           VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志：1=已删除 0=未删除（逻辑删除）',
    creater_um       VARCHAR(100) DEFAULT NULL                   COMMENT '创建人 UM 账号',
    updater_um       VARCHAR(100) DEFAULT NULL                   COMMENT '更新人 UM 账号',
    PRIMARY KEY (id),
    KEY idx_app_id (app_id),
    KEY idx_secret_hash (secret_hash),
    CONSTRAINT fk_appcred_app FOREIGN KEY (app_id) REFERENCES mg_app (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接入系统调用凭证';

-- ============================================================================
-- 5. mg_app_model_auth —— 接入系统对模型的授权
--    唯一键含 is_del：软删行不占用 (app_id, model_id)，支持撤销后重新授权
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_app_model_auth (
    id             BIGINT      NOT NULL AUTO_INCREMENT            COMMENT '主键，自增',
    app_id         VARCHAR(64) NOT NULL                          COMMENT '关联 mg_app.app_id',
    model_id       VARCHAR(64) NOT NULL                          COMMENT '关联 mg_model.model_id',
    enabled        TINYINT(1)  NOT NULL DEFAULT 1                COMMENT '是否启用：1=启用 0=禁用（禁用后该系统访问该模型返回 403）',
    qps_limit      INT         NOT NULL DEFAULT 0                COMMENT '每秒请求数上限，0=不限',
    daily_limit    INT         NOT NULL DEFAULT 0                COMMENT '每日请求数上限，0=不限',
    monthly_tokens BIGINT      NOT NULL DEFAULT 0                COMMENT '每月 Token 消耗上限，0=不限',
    payload_log_enabled TINYINT(1) NOT NULL DEFAULT 0            COMMENT '请求/响应报文记录开关：1=记录到 mg_request_payload，0=不记录（默认关闭）',
    expires_at     DATETIME                                     COMMENT '授权过期时间，NULL=永久授权（过期等同禁用）',
    creater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP        COMMENT '创建时间',
    updater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del         VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志：1=已删除 0=未删除（逻辑删除）',
    creater_um     VARCHAR(100) DEFAULT NULL                     COMMENT '创建人 UM 账号',
    updater_um     VARCHAR(100) DEFAULT NULL                     COMMENT '更新人 UM 账号',
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_model (app_id, model_id, is_del),
    KEY idx_model_id (model_id),
    CONSTRAINT fk_auth_app   FOREIGN KEY (app_id)   REFERENCES mg_app (app_id),
    -- 模型改名后授权关系继续指向同一模型；历史审计/报文表没有外键，仍保留旧 ID。
    CONSTRAINT fk_auth_model FOREIGN KEY (model_id) REFERENCES mg_model (model_id)
        ON UPDATE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='接入系统对模型的调用授权';

-- ============================================================================
-- 6. mg_access_rule —— 访问控制规则（IP / CIDR / 域名白名单，K8s 友好）
--    规则为空 = 不限制来源
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_access_rule (
    id           BIGINT       NOT NULL AUTO_INCREMENT             COMMENT '主键，自增',
    app_id       VARCHAR(64)  NOT NULL                           COMMENT '关联 mg_app.app_id',
    rule_type    VARCHAR(32)  NOT NULL                           COMMENT '规则类型：ip|cidr|domain|domain_wildcard',
    rule_value   VARCHAR(256) NOT NULL                           COMMENT '规则值：IP / CIDR 段 / 域名 / 通配域名',
    remark       VARCHAR(256)                                    COMMENT '备注',
    creater      VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP          COMMENT '创建时间',
    updater      VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志：1=已删除 0=未删除（逻辑删除）',
    creater_um   VARCHAR(100) DEFAULT NULL                       COMMENT '创建人 UM 账号',
    updater_um   VARCHAR(100) DEFAULT NULL                       COMMENT '更新人 UM 账号',
    PRIMARY KEY (id),
    KEY idx_app_id (app_id),
    CONSTRAINT fk_rule_app FOREIGN KEY (app_id) REFERENCES mg_app (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='访问控制规则（K8s 友好）';

-- ============================================================================
-- 7. mg_gateway_config —— 网关全局配置（KV 结构，热更新，无需重启）
--    ⚠️ 本表**无 is_del、物理删除**，与其余 6 张管理表的约定不同，是有意为之：
--       config_key 即主键，逻辑删除后行仍在，再存同名 key 会因 selectById 被过滤而走
--       insert → 主键冲突，导致"配置项删了就加不回来"。且配置非业务数据无留痕需求
--       （追溯有 updater / update_time）。对应实体 GatewayConfigEntity 亦不含 isDel 字段。
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_gateway_config (
    config_key    VARCHAR(128) NOT NULL                          COMMENT '配置键（主键，全局唯一）',
    config_value  TEXT         NOT NULL                          COMMENT '配置值',
    description   VARCHAR(256)                                   COMMENT '配置说明',
    creater       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP         COMMENT '创建时间',
    updater       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    creater_um    VARCHAR(100) DEFAULT NULL                      COMMENT '创建人 UM 账号',
    updater_um    VARCHAR(100) DEFAULT NULL                      COMMENT '更新人 UM 账号',
    PRIMARY KEY (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='网关动态配置（KV，物理删除）';

-- 初始配置（幂等：已存在则不覆盖）
-- 注：仅保留确有代码读取的配置项。
-- 已移除 'auth.enabled' 与 'rate.limit.default'：二者全链路无任何读取方（死开关），
-- 运维在管理页改动会显示成功但毫无作用，属误导性配置，详见 spec/modelgate/11 §16。
-- ============================================================================
-- 9. mg_request_payload —— 模型调用请求/响应报文留档
--    仅当 mg_app_model_auth.payload_log_enabled=1 时才写入；通过 trace_id 关联 mg_audit_log。
--    独立成表而非给审计表加列：审计是全量高频瘦表，报文只在开关开启时产生且体积大，
--    保留期也应远短于审计（modelgate.payload-log.retention-days）。
--    ⚠️ Token 不在本表冗余：唯一真相在 mg_audit_log，统计一律走审计表。
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_request_payload (
    id              BIGINT       NOT NULL AUTO_INCREMENT        COMMENT '主键，自增',
    trace_id        VARCHAR(64)  NOT NULL                       COMMENT '全链路追踪 ID，关联 mg_audit_log.trace_id',
    app_id          VARCHAR(64)                                 COMMENT '调用方 appId（冗余自审计，便于按接入系统直接筛选）',
    model_id        VARCHAR(64)                                 COMMENT '模型 ID（冗余自审计，便于按模型直接筛选）',
    is_stream       TINYINT(1)   NOT NULL DEFAULT 0             COMMENT '是否流式响应：1=SSE 流式，0=一次性返回',
    request_headers MEDIUMTEXT                                  COMMENT '全部入站请求头 JSON 快照，包含 Authorization/Cookie/API Key/Token/JWT/签名等凭证字段；代码限制单值与总字符数',
    request_body    LONGTEXT                                    COMMENT '请求报文原文（JSON）。⚠️ 含用户原始 prompt，脱敏方案待定',
    response_raw    LONGTEXT                                    COMMENT '响应报文原文：流式为完整 SSE 流（含 data: 前缀与所有分帧），非流式为原始 JSON。供排查时完整复现上游返回',
    response_text   LONGTEXT                                    COMMENT '响应正文：流式为各帧 delta.content 拼接后的最终文本，非流式为 choices[0].message.content。供查询展示，人可直读',
    finish_reason   VARCHAR(32)                                 COMMENT '结束原因：stop|length|tool_calls|content_filter 等，取自响应最后一帧',
    upstream_status INT                                         COMMENT '上游返回的 HTTP 状态码',
    -- ⚠️ 当前实现只会写入 none / response 两种值（PayloadService.toEntity）。
    -- request / both 是预留取值：请求侧目前没有截断机制（BodyParseFilter 用 DataBufferUtils.join
    -- 一次性读全请求体，没有字节上限，max-capture-bytes 只作用于响应采集侧），
    -- 除非将来给请求体也加采集上限，否则这两个值永远不会出现。管理面筛选只提供 none / response。
    truncated       VARCHAR(16)  NOT NULL DEFAULT 'none'        COMMENT '截断标记：none=完整 response=响应被截（触发内存保护阈值）。request/both 为预留值，当前实现不会写入',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录写入时间（用于保留期清理）',
    PRIMARY KEY (id),
    KEY idx_trace_id        (trace_id),
    KEY idx_app_model_time  (app_id, model_id, create_time),
    KEY idx_create_time     (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型调用请求/响应报文留档（按授权开关记录，保留期独立于审计）';


INSERT INTO mg_gateway_config (config_key, config_value, description) VALUES
    ('circuit.breaker.enabled', 'true',                  '熔断开关：true=开启（CircuitBreakerFilter 读取）')
ON DUPLICATE KEY UPDATE config_key = config_key;

-- ============================================================================
-- 8. mg_audit_log —— 调用审计日志（高频追加表，无基础字段，仅记录 create_time）
--    被 AuditCleanupService（按 create_time 清理）与用量统计 SQL 直接引用
-- ============================================================================
CREATE TABLE IF NOT EXISTS mg_audit_log (
    id             BIGINT        NOT NULL AUTO_INCREMENT          COMMENT '主键，自增',
    trace_id       VARCHAR(64)   NOT NULL                        COMMENT '全链路追踪 ID',
    app_id         VARCHAR(64)                                   COMMENT '调用方 appId（鉴权通过后写入）',
    model_id       VARCHAR(64)                                   COMMENT '请求的模型 ID',
    cred_type      VARCHAR(32)                                   COMMENT '凭证类型：api_key|ak_sk',
    client_ip      VARCHAR(64)                                   COMMENT '客户端 IP（X-Forwarded-For 优先）',
    caller_app_id  VARCHAR(64)                                   COMMENT '调用方系统标识（X-App-Id 请求头声明，非鉴权字段）',
    request_path   VARCHAR(512)                                  COMMENT '请求路径，如 /v1/chat/completions',
    http_status    INT                                           COMMENT '响应 HTTP 状态码',
    latency_ms     INT                                           COMMENT '请求耗时（毫秒）',
    input_tokens   INT           NOT NULL DEFAULT 0              COMMENT '输入 Token 数',
    output_tokens  INT           NOT NULL DEFAULT 0              COMMENT '输出 Token 数',
    token_source   VARCHAR(16)                                   COMMENT 'Token 来源口径：usage=上游精确返回 estimated=本地估算 none=无',
    error_msg      VARCHAR(1024)                                 COMMENT '错误信息（失败时记录）',
    create_time    DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '事件发生时间（写入时间）',
    PRIMARY KEY (id),
    KEY idx_trace_id    (trace_id),
    KEY idx_app_id      (app_id),
    KEY idx_model_id    (model_id),
    KEY idx_create_time (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='调用审计日志';
