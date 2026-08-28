-- ============================================================================
-- ModelGate 增量脚本 · 2026-08-28 · 服务连通性探测配置
-- ----------------------------------------------------------------------------
-- 背景：mg_service 原先只有上游地址，连通性探测固定用 GET 打根路径。
--   但接入的服务接口本身有自己的请求方式——有的只认 POST，有的根路径直接 404，
--   固定 GET 根路径的探测结果对这类服务没有参考价值。
--   改为在录入服务时一并登记「用什么方式探测」，探测按登记的来。
--
-- 三个新列都可为空/有默认值，存量数据不受影响（保持 GET + 根路径的旧行为）。
-- 脚本可重复执行（加列走存在性判断）。
--
-- 执行方式：
--   mysql -h<host> -P<port> -u<user> -p <库名> < sql/upgrade_20260828_service_probe.sql
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

-- 探测方法：默认 GET。
-- ⚠️ 选 POST/PUT/DELETE 意味着探测会对上游产生**真实副作用**——通用服务的写接口
--    可能真的建一条数据出来。管理页面上对非 GET 会显著警告，但最终由录入者决定。
CALL mg_add_column_if_absent('mg_service', 'probe_method',
    "probe_method VARCHAR(8) NOT NULL DEFAULT 'GET' COMMENT '连通性探测使用的 HTTP 方法：GET(默认,无副作用)|POST|PUT|DELETE|HEAD' AFTER sse");

-- 探测路径：留空则打上游根路径（与加列前的行为一致）
CALL mg_add_column_if_absent('mg_service', 'probe_path',
    "probe_path VARCHAR(256) DEFAULT NULL COMMENT '连通性探测路径，如 /health；留空则探测上游根路径' AFTER probe_method");

-- 探测请求体：仅在 probe_method 为 POST/PUT 等需要请求体时使用
CALL mg_add_column_if_absent('mg_service', 'probe_body',
    "probe_body TEXT DEFAULT NULL COMMENT '连通性探测请求体（JSON），仅 probe_method 需要请求体时使用' AFTER probe_path");

DROP PROCEDURE IF EXISTS mg_add_column_if_absent;
