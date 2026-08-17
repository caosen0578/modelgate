-- ============================================================================
-- 增量升级：mg_gateway_config 由逻辑删除改为物理删除
--
-- 背景
--   config_key 是主键。逻辑删除（is_del=1）后行仍在表中，再存同名 key 时
--   MyBatis-Plus 的 selectById 会被 @TableLogic 过滤而查不到 → 走 insert →
--   直接主键冲突。表现为「配置项删掉之后就再也加不回来」。
--   配置项不是业务数据，无留痕需求（追溯有 updater / update_time，变更本身也进审计），
--   故取消逻辑删除，改为物理删除。
--
-- 配套代码变更（必须同版本发布，勿单独执行本脚本）
--   - GatewayConfigEntity：移除 isDel 字段与 @TableLogic 注解
--   - GatewayConfigService.loadFromDb()：改为全量快照替换缓存（原实现只 put 不移除，
--     被删除的 key 会永远滞留在内存缓存中）
--
-- 影响范围
--   仅 mg_gateway_config 一张表。其余 6 张管理表的逻辑删除约定不变。
--
-- 执行环境：在目标库（如 cbdb）下直接执行，不含 CREATE DATABASE / USE
-- 幂等性：不幂等。重复执行时 DROP COLUMN 会因列不存在而报错，属预期，忽略即可。
-- ============================================================================

-- 步骤 1：清理历史逻辑删除数据
--   这些行在旧逻辑下对应用不可见，等价于"已删除"。
--   删除列之前必须先清掉，否则它们会突然变成有效配置生效，造成开关意外翻转。
DELETE FROM mg_gateway_config WHERE is_del = '1';

-- 步骤 2：删除逻辑删除标志列
ALTER TABLE mg_gateway_config DROP COLUMN is_del;

-- 步骤 3：更新表注释，与完整建表脚本 modelgate_schema.sql 保持一致
ALTER TABLE mg_gateway_config COMMENT = '网关动态配置（KV，物理删除）';

-- ============================================================================
-- 回滚脚本（仅在需要退回旧版本代码时执行）
-- ----------------------------------------------------------------------------
-- ALTER TABLE mg_gateway_config
--     ADD COLUMN is_del VARCHAR(50) CHARACTER SET utf8mb4 COLLATE utf8mb4_bin
--         DEFAULT '0' COMMENT '删除标志：1=已删除 0=未删除（逻辑删除）'
--         AFTER update_time;
-- ALTER TABLE mg_gateway_config COMMENT = '网关动态配置（KV）';
-- 注意：步骤 1 删除的历史软删数据无法恢复。
-- ============================================================================

-- 验证
-- SHOW COLUMNS FROM mg_gateway_config;          -- 应无 is_del
-- SELECT config_key, config_value FROM mg_gateway_config;
