-- ============================================================
-- ModelGate - MySQL 建表脚本
-- 字符集：utf8mb4，时区：Asia/Shanghai
-- ============================================================

CREATE DATABASE IF NOT EXISTS modelgate
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE modelgate;

-- 白名单主表
CREATE TABLE IF NOT EXISTS whitelist
(
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    api_key     VARCHAR(128)    NOT NULL COMMENT 'API Key，全局唯一',
    owner       VARCHAR(128)    NOT NULL COMMENT '调用方名称/团队',
    rate_limit  INT             NOT NULL DEFAULT 10 COMMENT '每秒限流数，0=不限流',
    allowed_ips VARCHAR(512)             DEFAULT NULL COMMENT '允许的来源 IP，逗号分隔，空=不校验',
    enabled     TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '是否启用：1=启用，0=禁用',
    remark      VARCHAR(256)             DEFAULT NULL COMMENT '备注',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_api_key (api_key),
    KEY idx_enabled (enabled),
    KEY idx_owner (owner)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'API Key 白名单';

-- 预置一条测试数据（本地开发用，生产删除）
INSERT INTO whitelist (api_key, owner, rate_limit, allowed_ips, enabled, remark)
VALUES ('mg-test-key-local', 'dev-team', 100, NULL, 1, '本地开发测试 Key，上线前删除');


-- ============================================================
-- 网关动态配置表（KV 结构）
-- 存储需要在生产环境实时修改、无需发版的配置，如上游地址
-- ============================================================
CREATE TABLE IF NOT EXISTS gateway_config
(
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
    config_key   VARCHAR(128)    NOT NULL COMMENT '配置 key，全局唯一',
    config_value TEXT            NOT NULL COMMENT '配置 value',
    description  VARCHAR(256)             DEFAULT NULL COMMENT '配置说明',
    updated_by   VARCHAR(128)             DEFAULT NULL COMMENT '最后修改人',
    created_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    updated_at   DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (id),
    UNIQUE KEY uk_config_key (config_key)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '网关动态配置（KV）';

-- 预置核心配置项
INSERT INTO gateway_config (config_key, config_value, description)
VALUES ('upstream.url',           'http://127.0.0.1:9090',  '上游模型服务地址，实时生效，无需重启'),
       ('auth.enabled',           'true',                   '鉴权总开关：true=开启，false=放行所有（紧急情况使用）'),
       ('circuit.breaker.enabled','true',                   '熔断开关：true=开启，false=关闭'),
       ('rate.limit.default',     '10',                     '默认每秒限流请求数（API Key 未单独配置时生效）')
ON DUPLICATE KEY UPDATE config_key = config_key; -- 已存在则跳过
