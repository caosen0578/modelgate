-- 仅对尚未升级的 ModelGate 库执行一次，先核对 SHOW CREATE TABLE mg_app。
-- 先部署结构、后部署代码；原系统自动使用 SYSTEM，不更新旧 app_id，不改现有字段/外键。
-- 个人 UM 唯一，系统 person_um=NULL。执行前按行内 TDSQL 规范确认锁表窗口与备份。
ALTER TABLE mg_app
    ADD COLUMN subject_type VARCHAR(16) NOT NULL DEFAULT 'SYSTEM' COMMENT '接入主体类型：SYSTEM 或 PERSONAL',
    ADD COLUMN person_um VARCHAR(64) NULL COMMENT '个人接入规范化 UM；系统为 NULL',
    ADD UNIQUE KEY uk_app_person_um (person_um);
