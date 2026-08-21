-- ModelGate 2026-08-21：报文记录增加全部入站请求头快照。
--
-- 生产约束：
--   1. 不做白名单或敏感字段排除，Authorization/Cookie/API Key/Token/JWT/签名等全部留档；
--   2. 上述凭证以明文保存，必须限制管理面和数据库访问权限，并依赖保留期及时物理清理；
--   3. request_headers 使用 MEDIUMTEXT 是为了容纳 JSON 转义后的极端值，代码仍将原始快照限制为
--      request-headers-max-total-chars（默认 16384 字符），并不会按 MEDIUMTEXT 上限采集；
--   4. 全模糊 LIKE 无法使用普通索引，不创建无效索引；查询由 TraceID/≤24h 时间窗护栏控制。
--
-- request_headers 刻意加在表尾且不指定 AFTER，以便 MySQL/TDSQL 尽可能采用 INSTANT/INPLACE DDL。
-- 生产执行前仍须确认数据库版本与长事务/元数据锁，并安排低峰窗口；脚本不强写 ALGORITHM，避免
-- 不同内部分支不支持指定算法时整条升级直接失败。

SET @has_request_headers := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'mg_request_payload'
       AND COLUMN_NAME = 'request_headers'
);
SET @add_request_headers_sql := IF(
    @has_request_headers = 0,
    'ALTER TABLE mg_request_payload ADD COLUMN request_headers MEDIUMTEXT NULL COMMENT ''全部入站请求头 JSON 快照（包含凭证字段；代码限制单值与总字符数）''',
    'SELECT ''mg_request_payload.request_headers already exists'''
);
PREPARE add_request_headers_stmt FROM @add_request_headers_sql;
EXECUTE add_request_headers_stmt;
DEALLOCATE PREPARE add_request_headers_stmt;

SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME = 'mg_request_payload'
   AND COLUMN_NAME = 'request_headers';
