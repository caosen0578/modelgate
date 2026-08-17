-- ============================================================================
-- 增量升级：mg_model 增加 upstream_path（上游接口路径）
--
-- 背景
--   此前上游接口路径没有配置项，行为散落在各适配器里写死：
--     openai / custom —— 透传调用方请求路径（即 /v1/chat/completions）
--     anthropic       —— 硬编码 /v1/messages
--     azure_openai    —— 忽略路径（upstream_url 本身即完整部署 URL）
--   这要求上游必须恰好监听与调用方相同的路径。上游若是 /api/generate、
--   /v1/openai/chat/completions 之类，真实转发就会 404——不只是连通性测试的问题。
--
-- 变更
--   mg_model 增加可空列 upstream_path VARCHAR(256)
--
-- 兼容性
--   **留空即维持原行为**，存量数据不需要回填，加完列即可继续跑。
--   Java 侧对该字段做空值兜底，故本脚本可与代码解耦发布（先发脚本或先发代码均可）。
--
-- 执行环境：在目标库（如 cbdb）下直接执行，不含 CREATE DATABASE / USE
-- 幂等性：不幂等。重复执行会因列已存在而报错，属预期。
-- ============================================================================

ALTER TABLE mg_model
    ADD COLUMN upstream_path VARCHAR(256) NULL
        COMMENT '上游接口路径，如 /v1/chat/completions；留空则按 api_spec 取默认值（openai/custom 透传调用方路径，anthropic 用 /v1/messages，azure 忽略本字段）'
        AFTER upstream_url;

-- ----------------------------------------------------------------------------
-- 可选：给存量模型显式写入当前实际生效的默认值，使配置从"隐式"变为"显式"。
-- 不执行也不影响功能（留空＝沿用原默认行为）；执行后运维在页面上能直接看到路径。
-- azure_openai 不回填：该规范忽略 upstream_path，写了反而误导。
-- ----------------------------------------------------------------------------
-- UPDATE mg_model SET upstream_path = '/v1/chat/completions'
--     WHERE upstream_path IS NULL AND api_spec IN ('openai', 'custom') AND model_type = 'chat';
-- UPDATE mg_model SET upstream_path = '/v1/embeddings'
--     WHERE upstream_path IS NULL AND api_spec IN ('openai', 'custom') AND model_type = 'embedding';
-- UPDATE mg_model SET upstream_path = '/v1/messages'
--     WHERE upstream_path IS NULL AND api_spec = 'anthropic';

-- ============================================================================
-- 回滚
-- ----------------------------------------------------------------------------
-- ALTER TABLE mg_model DROP COLUMN upstream_path;
-- ============================================================================

-- 验证
-- SHOW COLUMNS FROM mg_model LIKE 'upstream_path';
