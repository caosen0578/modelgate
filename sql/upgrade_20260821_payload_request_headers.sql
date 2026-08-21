-- ModelGate 2026-08-21：报文记录增加请求头白名单快照。
--
-- 生产约束：
--   1. 只保存 modelgate.payload-log.request-header-allowlist 中的 Header；
--   2. Authorization/Cookie/API Key/Credential/Token/JWT/Session-ID/签名类 Header 在代码中有
--      硬拒绝名单，即使误配置也不落库；白名单 Header 的值本身也不得夹带密钥；
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
    'ALTER TABLE mg_request_payload ADD COLUMN request_headers MEDIUMTEXT NULL COMMENT ''请求头白名单 JSON 快照（严禁凭证类 Header；代码限制采集总长度）''',
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
