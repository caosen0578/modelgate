-- ============================================================
-- ModelGate · V2 存量库升级脚本（已按旧 schema 建库的环境执行）
-- 变更内容：
--   1) created_at/updated_at 重命名为 create_time/update_time
--   2) 追加内部规范基础字段（creater/updater/is_del/creater_um/updater_um）
--   3) mg_app_model_auth 唯一键纳入 is_del（软删行不占用唯一键）
-- 注意：
--   - mg_audit_log 不动（保留 created_at）
--   - MySQL 8 的 ADD COLUMN ... DEFAULT '0' 会以默认值回填存量行；
--     执行后请抽查 is_del 均为 '0'
--   - 执行前备份，建议在变更窗口内执行
-- ============================================================

USE modelgate;

-- 1. mg_model
ALTER TABLE mg_model
    CHANGE created_at create_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE updated_at update_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    ADD COLUMN creater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    ADD COLUMN updater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    ADD COLUMN is_del     VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0'  COMMENT '删除标志(1-已删除 0-未删除)',
    ADD COLUMN creater_um VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    ADD COLUMN updater_um VARCHAR(100) DEFAULT NULL COMMENT '更新人um';

-- 2. mg_model_credential
ALTER TABLE mg_model_credential
    CHANGE created_at create_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE updated_at update_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    ADD COLUMN creater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    ADD COLUMN updater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    ADD COLUMN is_del     VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0'  COMMENT '删除标志(1-已删除 0-未删除)',
    ADD COLUMN creater_um VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    ADD COLUMN updater_um VARCHAR(100) DEFAULT NULL COMMENT '更新人um';

-- 3. mg_app
ALTER TABLE mg_app
    CHANGE created_at create_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE updated_at update_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    ADD COLUMN creater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    ADD COLUMN updater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    ADD COLUMN is_del     VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0'  COMMENT '删除标志(1-已删除 0-未删除)',
    ADD COLUMN creater_um VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    ADD COLUMN updater_um VARCHAR(100) DEFAULT NULL COMMENT '更新人um';

-- 4. mg_app_credential
ALTER TABLE mg_app_credential
    CHANGE created_at create_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE updated_at update_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    ADD COLUMN creater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    ADD COLUMN updater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    ADD COLUMN is_del     VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0'  COMMENT '删除标志(1-已删除 0-未删除)',
    ADD COLUMN creater_um VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    ADD COLUMN updater_um VARCHAR(100) DEFAULT NULL COMMENT '更新人um';

-- 5. mg_app_model_auth（含唯一键重建）
ALTER TABLE mg_app_model_auth
    CHANGE created_at create_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    CHANGE updated_at update_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    ADD COLUMN creater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    ADD COLUMN updater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    ADD COLUMN is_del     VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0'  COMMENT '删除标志(1-已删除 0-未删除)',
    ADD COLUMN creater_um VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    ADD COLUMN updater_um VARCHAR(100) DEFAULT NULL COMMENT '更新人um',
    DROP KEY uk_app_model,
    ADD UNIQUE KEY uk_app_model (app_id, model_id, is_del);

-- 6. mg_access_rule（原表只有 created_at，需补 update_time）
ALTER TABLE mg_access_rule
    CHANGE created_at create_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    ADD COLUMN update_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    ADD COLUMN creater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    ADD COLUMN updater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    ADD COLUMN is_del     VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0'  COMMENT '删除标志(1-已删除 0-未删除)',
    ADD COLUMN creater_um VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    ADD COLUMN updater_um VARCHAR(100) DEFAULT NULL COMMENT '更新人um';

-- 7. mg_gateway_config（原表只有 updated_at，需补 create_time）
ALTER TABLE mg_gateway_config
    CHANGE updated_at update_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间',
    ADD COLUMN create_time DATETIME NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    ADD COLUMN creater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '创建人',
    ADD COLUMN updater    VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT NULL COMMENT '修改人',
    ADD COLUMN is_del     VARCHAR(50)  CHARACTER SET utf8mb4 COLLATE utf8mb4_bin DEFAULT '0'  COMMENT '删除标志(1-已删除 0-未删除)',
    ADD COLUMN creater_um VARCHAR(100) DEFAULT NULL COMMENT '创建人um',
    ADD COLUMN updater_um VARCHAR(100) DEFAULT NULL COMMENT '更新人um';

-- ============================================================
-- 验证（执行后抽查）
-- ============================================================
-- SELECT COUNT(*) FROM mg_model WHERE is_del IS NULL;              -- 应为 0
-- SHOW INDEX FROM mg_app_model_auth WHERE Key_name='uk_app_model'; -- 应含 is_del 列
