-- ============================================================================
-- upgrade_20260813_remove_dead_gateway_config.sql
--
-- 目的：清理 mg_gateway_config 中的「死开关」——全链路没有任何代码读取的配置项。
--
-- 背景：管理页「网关配置 → ⚡ 全局开关」卡片上 4 个控件，经代码走查确认 3 个是摆设：
--
--   | 配置项                  | 读取方                              | 结论   |
--   |-------------------------|-------------------------------------|--------|
--   | circuit.breaker.enabled | CircuitBreakerFilter:81             | 有效   |
--   | auth.enabled            | 无（CredentialAuthFilter 压根不看） | 死开关 |
--   | rate.limit.default      | 无（限流走授权表的 qps_limit）      | 死开关 |
--   | aksk.window.seconds     | 无（±5min 硬编码在 CredentialAuth） | 死开关 |
--
-- 危害：运维改 auth.enabled 以为能"紧急放行"，点保存会提示成功，实际一点作用没有；
--       出安全事件时按这个开关操作会延误处置。与此前移除的 upstream.url 是同一类问题。
--
-- 本脚本纯数据清理，不改表结构；aksk.window.seconds 从未落过初始数据，
-- 但历史上运维可能通过页面存过，故一并删除。
--
-- 配套代码改动（必须同版本发布，否则页面仍会把这些 key 写回来）：
--   · GatewayConfigService  删 isAuthEnabled() / getDefaultRateLimit()
--   · GatewayConfigEntity   删 KEY_AUTH_ENABLED / KEY_DEFAULT_RATE_LIMIT
--   · DynamicConfigHolder   删 authEnabled 字段与 modelgate.auth.enabled 配置项
--   · admin.html            删「鉴权总开关」「默认全局 QPS 上限」「AK/SK 时间窗口」三个控件
--
-- 执行：mysql -h.. -P.. -u.. -p 库名 < sql/upgrade_20260813_remove_dead_gateway_config.sql
-- ============================================================================

-- 前置检查：确认将被删除的行（执行前先看一眼，确保没有被挪作他用）
SELECT config_key, config_value, description, update_time
FROM mg_gateway_config
WHERE config_key IN ('auth.enabled', 'rate.limit.default', 'aksk.window.seconds');

-- 删除死开关
-- 注：mg_gateway_config 是 7 张管理表中唯一不做逻辑删除的（config_key 即主键，
--     软删后同 key 无法重建），物理删除是设计如此，见建表脚本中的说明。
DELETE FROM mg_gateway_config
WHERE config_key IN ('auth.enabled', 'rate.limit.default', 'aksk.window.seconds');

-- 校验：应只剩 circuit.breaker.enabled（以及运维自行新增的其它 key）
SELECT config_key, config_value, description FROM mg_gateway_config ORDER BY config_key;

-- ============================================================================
-- 回滚脚本（如需恢复原状；注意恢复后这些 key 依旧不生效，仅为还原数据）
-- ============================================================================
-- INSERT INTO mg_gateway_config (config_key, config_value, description) VALUES
--     ('auth.enabled',       'true', '鉴权总开关：true=开启，false=放行所有'),
--     ('rate.limit.default', '10',   '默认每秒限流数（无单独配置时生效）')
-- ON DUPLICATE KEY UPDATE config_key = config_key;
