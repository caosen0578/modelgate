-- 先执行此增量再发布网关。普通接入的审计行保留 NULL。
ALTER TABLE mg_audit_log
    ADD COLUMN codebuddy_user_id VARCHAR(36) NULL COMMENT 'CodeBuddy 调用的用户 UUID' AFTER caller_app_id;
