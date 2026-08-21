-- ModelGate 2026-08-20：拆分「系统名称」与「应用名称」。
--
-- 历史上 app_name 虽然物理列名是「应用名称」，管理页面却一直把它标成「系统名称」。
-- 新增 system_name 承接系统中文名，app_name 回归应用中文名。存量无法自动推断真实应用名，
-- 因此先复制旧值保证升级后非空且页面可用，运维再从接入系统编辑页逐条校正应用名称。

SET @has_system_name := (
    SELECT COUNT(*)
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'mg_app'
       AND COLUMN_NAME = 'system_name'
);
SET @add_system_name_sql := IF(
    @has_system_name = 0,
    'ALTER TABLE mg_app ADD COLUMN system_name VARCHAR(128) NULL COMMENT ''所属业务系统中文名称'' AFTER app_id',
    'SELECT ''mg_app.system_name already exists'''
);
PREPARE add_system_name_stmt FROM @add_system_name_sql;
EXECUTE add_system_name_stmt;
DEALLOCATE PREPARE add_system_name_stmt;

-- 幂等回填：只补空值，不覆盖已经人工维护过的系统名称。
UPDATE mg_app
   SET system_name = app_name
 WHERE system_name IS NULL OR TRIM(system_name) = '';

-- 回填完成后再收紧非空约束；已经是 NOT NULL 时不重复做 ALTER，避免脚本误重跑时再拿元数据锁。
SET @system_name_nullable := (
    SELECT IS_NULLABLE
      FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE()
       AND TABLE_NAME = 'mg_app'
       AND COLUMN_NAME = 'system_name'
);
SET @not_null_system_name_sql := IF(
    @system_name_nullable = 'YES',
    'ALTER TABLE mg_app MODIFY COLUMN system_name VARCHAR(128) NOT NULL COMMENT ''所属业务系统中文名称''',
    'SELECT ''mg_app.system_name is already NOT NULL'''
);
PREPARE not_null_system_name_stmt FROM @not_null_system_name_sql;
EXECUTE not_null_system_name_stmt;
DEALLOCATE PREPARE not_null_system_name_stmt;
