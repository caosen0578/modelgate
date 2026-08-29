-- ============================================================================
-- ModelGate 增量脚本 · 2026-08-29 · 报文回放所需的请求方法与路径
-- ----------------------------------------------------------------------------
-- 背景：新增「报文记录 → 回放」功能——把出问题的那一发请求重新打给上游复现。
--   模型报文不需要这两列：模型侧上游路径由 mg_model.upstream_path / api_spec 决定，
--   服务端能自己算出来。
--   服务报文（traffic_type='service'）不行：上游路径 = upstream_url + 剥掉
--   /svc/{编码} 后的剩余路径，而**剩余路径只存在于当时那个请求的 URL 里**，
--   本表一个字段都没留，回放时只能让人手工回忆着填。
--
-- 两个新列均可空，存量数据不受影响（老报文回放时前端提示"需手工填写路径"）。
-- 脚本可重复执行（加列走存在性判断）。
--
-- ⚠️ mg_request_payload 是**高频追加表**，执行前先 SELECT COUNT(*)；
--    行数上千万时挑低峰，或走 pt-online-schema-change。
--    （本次只加两个可空小列、不建索引，代价远小于 upgrade_20260827 那次。）
--
-- 执行方式：
--   mysql -h<host> -P<port> -u<user> -p <库名> < sql/upgrade_20260829_payload_replay.sql
-- ============================================================================

DROP PROCEDURE IF EXISTS mg_add_column_if_absent;
DELIMITER $$
CREATE PROCEDURE mg_add_column_if_absent(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_ddl    TEXT)
BEGIN
    IF NOT EXISTS (SELECT 1 FROM information_schema.COLUMNS
                   WHERE TABLE_SCHEMA = DATABASE()
                     AND TABLE_NAME   = p_table
                     AND COLUMN_NAME  = p_column) THEN
        SET @s = CONCAT('ALTER TABLE `', p_table, '` ADD COLUMN ', p_ddl);
        PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- 入站请求方法。模型流量恒为 POST，服务流量由接入方的接口决定。
CALL mg_add_column_if_absent('mg_request_payload', 'request_method',
    "request_method VARCHAR(10) DEFAULT NULL COMMENT '入站请求的 HTTP 方法（回放用）。模型流量恒为 POST，服务流量由接入方接口决定；本列加入前的历史报文为 NULL' AFTER is_stream");

-- 入站请求路径（含 query）。回放服务报文时据此还原上游路径。
-- 512 足够：网关对外路径是 /svc/{编码}/... 或 /v1/...，超长 query 会被截断，
-- 截断只影响回放的便利性，不影响留档本身。
CALL mg_add_column_if_absent('mg_request_payload', 'request_path',
    "request_path VARCHAR(512) DEFAULT NULL COMMENT '入站请求路径含 query（回放用），如 /svc/crm/api/v2/query?x=1；本列加入前的历史报文为 NULL' AFTER request_method");

DROP PROCEDURE IF EXISTS mg_add_column_if_absent;
