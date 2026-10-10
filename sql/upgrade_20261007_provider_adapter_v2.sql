-- ProviderAdapter V2：先执行增量 DDL，再部署后端；不操作任何业务记录/授权。
-- 仅对尚未升级的库执行一次；先检查 information_schema.columns。
-- 存量模型仍默认原生响应，启用 OPENAI_COMPATIBLE 必须显式修改模型配置。
ALTER TABLE mg_model
    MODIFY COLUMN api_spec VARCHAR(64) NOT NULL DEFAULT 'openai' COMMENT '适配器 ID',
    ADD COLUMN response_mode VARCHAR(32) NOT NULL DEFAULT 'PASSTHROUGH' COMMENT 'PASSTHROUGH|OPENAI_COMPATIBLE' AFTER api_spec,
    ADD COLUMN config_version INT NOT NULL DEFAULT 1 AFTER response_mode,
    ADD COLUMN adapter_config TEXT COMMENT '非机密协议配置 JSON' AFTER config_version,
    MODIFY COLUMN timeout_ms INT NOT NULL DEFAULT 30000 COMMENT '仅管理探测/对话测试超时参考，业务模型转发不使用';
