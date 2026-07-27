-- ============================================================
-- ModelGate · V1 基线建表脚本（全新环境执行）
-- 字符集：utf8mb4  时区：Asia/Shanghai
-- 存量环境请勿执行本脚本，改用 V2__upgrade_base_fields.sql
--
-- 内部规范基础字段（7 张管理表均含，审计表除外）：
--   creater / create_time / updater / update_time
--   is_del（逻辑删除 '0'/'1'）/ creater_um / updater_um
-- 由 AuditFieldMetaObjectHandler 自动填充；is_del 由 @TableLogic 驱动
-- ============================================================

CREATE DATABASE IF NOT EXISTS modelgate
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE modelgate;

-- ============================================================
-- 1. 模型实例
-- ============================================================
CREATE TABLE IF NOT EXISTS mg_model (
    id            BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model_id      VARCHAR(64)  NOT NULL UNIQUE COMMENT '对外暴露的模型标识，如 gpt-4o',
    model_name    VARCHAR(128) NOT NULL COMMENT '展示名称',
    provider      VARCHAR(32)  NOT NULL COMMENT 'openai|azure_openai|anthropic|private|custom',
    model_type    VARCHAR(32)  NOT NULL DEFAULT 'chat'   COMMENT 'chat|embedding|image|audio',
    api_spec      VARCHAR(32)  NOT NULL DEFAULT 'openai' COMMENT 'openai|azure_openai|anthropic|custom',
    upstream_url  VARCHAR(512) NOT NULL COMMENT '上游请求地址',
    timeout_ms    INT          NOT NULL DEFAULT 30000    COMMENT '超时毫秒',
    default_params TEXT                 COMMENT '默认参数 JSON，业务可覆盖',
    force_params  TEXT                  COMMENT '强制参数 JSON，业务不可覆盖',
    param_mapping TEXT                  COMMENT '自定义字段映射 JSON，仅 api_spec=custom 时使用',
    description   VARCHAR(512),
    enabled       TINYINT(1)   NOT NULL DEFAULT 1,
    creater       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time   DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updater       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time   DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志(1-已删除 0-未删除)',
    creater_um    VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    updater_um    VARCHAR(100) DEFAULT NULL COMMENT '更新人um'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='AI 模型实例';

-- ============================================================
-- 2. 模型上游鉴权凭证（与 mg_model 一对一）
-- ============================================================
CREATE TABLE IF NOT EXISTS mg_model_credential (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    model_id       VARCHAR(64)  NOT NULL UNIQUE COMMENT '关联 mg_model.model_id',
    auth_type      VARCHAR(32)  NOT NULL COMMENT 'api_key|ak_sk|azure|none',
    access_key     VARCHAR(256)          COMMENT 'AK 或 Azure resource name，明文',
    secret_key     VARCHAR(512)          COMMENT '加密存储（AES-256-GCM）',
    extra_config   TEXT                  COMMENT 'Azure deployment_id/api_version 等，JSON',
    creater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del         VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志(1-已删除 0-未删除)',
    creater_um     VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    updater_um     VARCHAR(100) DEFAULT NULL COMMENT '更新人um',
    FOREIGN KEY (model_id) REFERENCES mg_model(model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='模型上游鉴权凭证';

-- ============================================================
-- 3. 接入系统（调用方应用）
-- ============================================================
CREATE TABLE IF NOT EXISTS mg_app (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_id     VARCHAR(64)  NOT NULL UNIQUE COMMENT '应用唯一标识，由网关生成',
    app_name   VARCHAR(128) NOT NULL COMMENT '应用名称',
    sys_code   VARCHAR(64)  NOT NULL COMMENT '所属系统编码，如 CRM、BI',
    app_code   VARCHAR(64)  NOT NULL COMMENT '应用编码，系统内唯一',
    owner      VARCHAR(64)  NOT NULL COMMENT '负责人',
    remark     VARCHAR(512),
    enabled    TINYINT(1)   NOT NULL DEFAULT 1,
    creater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del     VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志(1-已删除 0-未删除)',
    creater_um VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    updater_um VARCHAR(100) DEFAULT NULL COMMENT '更新人um'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接入系统';

-- ============================================================
-- 4. 接入系统调用凭证
-- ============================================================
CREATE TABLE IF NOT EXISTS mg_app_credential (
    id                BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_id            VARCHAR(64)  NOT NULL COMMENT '关联 mg_app.app_id',
    cred_name         VARCHAR(128) NOT NULL COMMENT '凭证名称',
    cred_type         VARCHAR(32)  NOT NULL COMMENT 'api_key|ak_sk',
    access_key        VARCHAR(128)          COMMENT 'AK 明文 / API Key 末4位（仅展示用）',
    secret_hash       VARCHAR(128)          COMMENT 'SHA-256(api_key) 或 SHA-256(sk)，用于校验',
    secret_encrypted  VARCHAR(512)          COMMENT '加密后的 SK，仅 ak_sk 类型使用',
    enabled           TINYINT(1)   NOT NULL DEFAULT 1,
    expires_at        DATETIME              COMMENT 'NULL=永久有效',
    remark            VARCHAR(512),
    creater           VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time       DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updater           VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time       DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del            VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志(1-已删除 0-未删除)',
    creater_um        VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    updater_um        VARCHAR(100) DEFAULT NULL COMMENT '更新人um',
    FOREIGN KEY (app_id) REFERENCES mg_app(app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='接入系统调用凭证';

-- ============================================================
-- 5. 接入系统对模型的授权
--    唯一键含 is_del：软删行不占用 (app_id, model_id)，支持撤销后重新授权
-- ============================================================
CREATE TABLE IF NOT EXISTS mg_app_model_auth (
    id             BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_id         VARCHAR(64) NOT NULL COMMENT '关联 mg_app.app_id',
    model_id       VARCHAR(64) NOT NULL COMMENT '关联 mg_model.model_id',
    enabled        TINYINT(1)  NOT NULL DEFAULT 1,
    qps_limit      INT         NOT NULL DEFAULT 0      COMMENT '每秒请求数上限，0=不限',
    daily_limit    INT         NOT NULL DEFAULT 0      COMMENT '每日请求数上限，0=不限',
    monthly_tokens BIGINT      NOT NULL DEFAULT 0      COMMENT '每月 Token 上限，0=不限',
    expires_at     DATETIME             COMMENT 'NULL=永久授权',
    creater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time    DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updater        VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time    DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del         VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志(1-已删除 0-未删除)',
    creater_um     VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    updater_um     VARCHAR(100) DEFAULT NULL COMMENT '更新人um',
    UNIQUE KEY uk_app_model (app_id, model_id, is_del),
    FOREIGN KEY (app_id)   REFERENCES mg_app(app_id),
    FOREIGN KEY (model_id) REFERENCES mg_model(model_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='应用对模型的调用授权';

-- ============================================================
-- 6. 访问控制规则（IP / CIDR / 域名白名单，K8s 友好）
--   ip / cidr / domain / domain_wildcard；规则为空 = 不限制来源
-- ============================================================
CREATE TABLE IF NOT EXISTS mg_access_rule (
    id         BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    app_id     VARCHAR(64)  NOT NULL COMMENT '关联 mg_app.app_id',
    rule_type  VARCHAR(32)  NOT NULL COMMENT 'ip|cidr|domain|domain_wildcard',
    rule_value VARCHAR(256) NOT NULL COMMENT 'IP / CIDR 段 / 域名 / 通配域名',
    remark     VARCHAR(256),
    creater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time DATETIME    DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time DATETIME    DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del     VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志(1-已删除 0-未删除)',
    creater_um VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    updater_um VARCHAR(100) DEFAULT NULL COMMENT '更新人um',
    FOREIGN KEY (app_id) REFERENCES mg_app(app_id),
    INDEX idx_app_id (app_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='访问控制规则（K8s 友好）';

-- ============================================================
-- 7. 网关全局配置（KV 结构，热更新，无需重启）
-- ============================================================
CREATE TABLE IF NOT EXISTS mg_gateway_config (
    config_key   VARCHAR(128) NOT NULL PRIMARY KEY COMMENT '配置键，全局唯一',
    config_value TEXT         NOT NULL              COMMENT '配置值',
    description  VARCHAR(256)                       COMMENT '说明',
    updated_by   VARCHAR(64)                        COMMENT '最后修改人（X-Admin-User 透传）',
    creater      VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    create_time  DATETIME     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updater      VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    update_time  DATETIME     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    is_del       VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0' COMMENT '删除标志(1-已删除 0-未删除)',
    creater_um   VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    updater_um   VARCHAR(100) DEFAULT NULL COMMENT '更新人um'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='网关动态配置（KV）';

INSERT INTO mg_gateway_config (config_key, config_value, description) VALUES
    ('upstream.url',            'http://127.0.0.1:9090', '上游模型服务地址，实时生效'),
    ('auth.enabled',            'true',                  '鉴权总开关：true=开启，false=放行所有'),
    ('circuit.breaker.enabled', 'true',                  '熔断开关'),
    ('rate.limit.default',      '10',                    '默认每秒限流数（无单独配置时生效）')
ON DUPLICATE KEY UPDATE config_key = config_key;

-- ============================================================
-- 8. 审计日志（高频追加表，无基础字段，仅 created_at；被清理/统计 SQL 直接引用）
-- ============================================================
CREATE TABLE IF NOT EXISTS mg_audit_log (
    id             BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id       VARCHAR(64)   NOT NULL              COMMENT '全链路追踪 ID',
    app_id         VARCHAR(64)                         COMMENT '调用方 appId',
    model_id       VARCHAR(64)                         COMMENT '请求的模型 ID',
    cred_type      VARCHAR(32)                         COMMENT '凭证类型：api_key|ak_sk',
    client_ip      VARCHAR(64)                         COMMENT '客户端 IP（X-Forwarded-For 优先）',
    caller_app_id  VARCHAR(64)                         COMMENT '调用方系统标识（X-App-Id 头）',
    request_path   VARCHAR(512),
    http_status    INT,
    latency_ms     INT,
    input_tokens   INT           NOT NULL DEFAULT 0,
    output_tokens  INT           NOT NULL DEFAULT 0,
    error_msg      VARCHAR(1024),
    created_at     DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_trace_id   (trace_id),
    INDEX idx_app_id     (app_id),
    INDEX idx_model_id   (model_id),
    INDEX idx_created_at (created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='调用审计日志';

-- ============================================================
-- 旧表（保留，勿删，兼容升级前数据）
-- ============================================================
CREATE TABLE IF NOT EXISTS whitelist (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    api_key     VARCHAR(128)    NOT NULL,
    owner       VARCHAR(128)    NOT NULL,
    rate_limit  INT             NOT NULL DEFAULT 10,
    allowed_ips VARCHAR(512)             DEFAULT NULL,
    enabled     TINYINT(1)      NOT NULL DEFAULT 1,
    remark      VARCHAR(256)             DEFAULT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_key (api_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旧版白名单（已废弃，保留兼容）';

CREATE TABLE IF NOT EXISTS gateway_config (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    config_key   VARCHAR(128)    NOT NULL,
    config_value TEXT            NOT NULL,
    description  VARCHAR(256)             DEFAULT NULL,
    updated_by   VARCHAR(128)             DEFAULT NULL,
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='旧版配置表（已废弃，保留兼容）';
