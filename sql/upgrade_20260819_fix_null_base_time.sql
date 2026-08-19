-- ============================================================================
-- upgrade_20260819_fix_null_base_time.sql
--
-- 目的：排查并修复「新增数据的 create_time / update_time 为空」。
--       现象最早在内网环境的 mg_app 上发现（新增接入系统后时间字段为空），
--       但成因是全局性的，其它表只是恰好没有新插入的行，不代表没问题。
--
-- ⚠️ 本脚本分两部分：**第一部分是只读诊断，先跑它**；
--    第二部分是修复，**看完诊断结果再决定跑不跑哪几段**。不要整个文件一把梭。
--
-- 【成因说明】
--   基础字段本应由 AuditFieldMetaObjectHandler 通过 MyBatis-Plus 的
--   strictInsertFill 自动填充。该机制生效需要两个条件同时满足：
--     (1) 实体字段标了 @TableField(fill = INSERT / INSERT_UPDATE)  —— 代码里已齐全，无问题
--     (2) MetaObjectHandler 被挂到了 SqlSessionFactory 的 GlobalConfig 上 —— **这一条依赖装配**
--
--   自定义 SqlSessionFactory 会让 MybatisPlusAutoConfiguration 整体退避，
--   (2) 必须手动补。漏了不会报任何错。漏了之后：
--     · 若全局 insertStrategy = NOT_NULL（MP 默认）：null 列被排除出 INSERT，
--       建表的 DEFAULT CURRENT_TIMESTAMP 仍会生效 → 时间字段**有值**，问题被掩盖；
--     · 若全局 insertStrategy 会写出显式 NULL（部分框架会这么配）：
--       显式 NULL **覆盖** DEFAULT → 时间字段**为空**。
--   同一份代码在两个环境表现不同，根源就在这里。
--
--   本仓库已有 MybatisWiringSelfCheck 在启动时断言 (2)，缺失即启动失败并给出修复指引。
--   该类已改为按类型注入、不依赖 bean 名，**内网合并版请务必一并引入**。
-- ============================================================================


-- ============================================================================
-- 第一部分：只读诊断（先跑这段，不改任何数据）
-- ============================================================================

-- ① 各表时间字段的空值分布。
--    若「仅个别表为空」→ 多半是那张表新插入过行，而其它表的行是早先建的；
--    若「建表默认值」那一列显示 NULL → 是建表时就缺 DEFAULT，属另一个独立问题。
SELECT 'mg_app'            AS 表名, COUNT(*) AS 总行数,
       SUM(create_time IS NULL) AS 创建时间为空, SUM(update_time IS NULL) AS 更新时间为空 FROM mg_app
UNION ALL SELECT 'mg_model',            COUNT(*), SUM(create_time IS NULL), SUM(update_time IS NULL) FROM mg_model
UNION ALL SELECT 'mg_model_credential', COUNT(*), SUM(create_time IS NULL), SUM(update_time IS NULL) FROM mg_model_credential
UNION ALL SELECT 'mg_app_credential',   COUNT(*), SUM(create_time IS NULL), SUM(update_time IS NULL) FROM mg_app_credential
UNION ALL SELECT 'mg_app_model_auth',   COUNT(*), SUM(create_time IS NULL), SUM(update_time IS NULL) FROM mg_app_model_auth
UNION ALL SELECT 'mg_access_rule',      COUNT(*), SUM(create_time IS NULL), SUM(update_time IS NULL) FROM mg_access_rule
UNION ALL SELECT 'mg_gateway_config',   COUNT(*), SUM(create_time IS NULL), SUM(update_time IS NULL) FROM mg_gateway_config;

-- ② 建表时到底有没有 DEFAULT CURRENT_TIMESTAMP。
--    COLUMN_DEFAULT 为 NULL 说明这张表**缺默认值**，即使自动填充失效也没有兜底。
SELECT TABLE_NAME AS 表名, COLUMN_NAME AS 列名,
       COLUMN_DEFAULT AS 建表默认值, IS_NULLABLE AS 可空, EXTRA AS 附加
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND COLUMN_NAME IN ('create_time', 'update_time')
   AND TABLE_NAME LIKE 'mg\\_%'
 ORDER BY TABLE_NAME, COLUMN_NAME;

-- ③ creater / updater 是否也全为空。
--    若时间为空**且** creater 也全为空 → 指向「自动填充整体没生效」；
--    若时间为空但 creater 有值 → 指向「只是建表少了 DEFAULT」，两者修法不同。
--    注：本项目 OperatorProvider 目前是占位实现，恒返回 null，
--        所以 creater 为空是**预期**的，这一项只用于交叉印证，不要单独据此下结论。
SELECT COUNT(*) AS mg_app总行数,
       SUM(creater IS NULL) AS 创建人为空,
       SUM(create_time IS NULL) AS 创建时间为空
  FROM mg_app;


-- ============================================================================
-- 第二部分：修复（看完诊断再执行对应段落）
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 修复 A：回填已有的空值。
--
-- 回填值用 update_time 兜底，都没有就用当前时间——**这是估算值，不是真实创建时间**，
-- 真实时间已经丢了，找不回来。仅为让排序/展示不再出现空白。
-- 若这些行的准确创建时间对审计有意义，先从别处（日志、审计表）核对再决定要不要回填。
--
-- 逐表执行，跑之前先用上面 ① 的结果确认这张表确实有空值，没有就跳过。
-- ---------------------------------------------------------------------------
-- UPDATE mg_app            SET create_time = COALESCE(create_time, update_time, NOW()),
--                              update_time = COALESCE(update_time, create_time, NOW())
--  WHERE create_time IS NULL OR update_time IS NULL;
-- UPDATE mg_model            SET create_time = COALESCE(create_time, update_time, NOW()), update_time = COALESCE(update_time, create_time, NOW()) WHERE create_time IS NULL OR update_time IS NULL;
-- UPDATE mg_model_credential SET create_time = COALESCE(create_time, update_time, NOW()), update_time = COALESCE(update_time, create_time, NOW()) WHERE create_time IS NULL OR update_time IS NULL;
-- UPDATE mg_app_credential   SET create_time = COALESCE(create_time, update_time, NOW()), update_time = COALESCE(update_time, create_time, NOW()) WHERE create_time IS NULL OR update_time IS NULL;
-- UPDATE mg_app_model_auth   SET create_time = COALESCE(create_time, update_time, NOW()), update_time = COALESCE(update_time, create_time, NOW()) WHERE create_time IS NULL OR update_time IS NULL;
-- UPDATE mg_access_rule      SET create_time = COALESCE(create_time, update_time, NOW()), update_time = COALESCE(update_time, create_time, NOW()) WHERE create_time IS NULL OR update_time IS NULL;
-- UPDATE mg_gateway_config   SET create_time = COALESCE(create_time, update_time, NOW()), update_time = COALESCE(update_time, create_time, NOW()) WHERE create_time IS NULL OR update_time IS NULL;

-- ---------------------------------------------------------------------------
-- 修复 B：补上缺失的 DEFAULT CURRENT_TIMESTAMP。
--
-- **仅当上面 ② 显示该表的 COLUMN_DEFAULT 为 NULL 时才执行对应行。**
-- 已经有默认值的表不要跑，白白 ALTER 一次没有收益。
--
-- 注意：DEFAULT 只是兜底，挡不住"显式写 NULL"。真正的修复是让 MetaObjectHandler 装配正确
-- （见修复 C）。两者都做才算完整——DEFAULT 防的是"没填"，装配防的是"填错"。
-- ---------------------------------------------------------------------------
-- ALTER TABLE mg_app MODIFY COLUMN create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间';
-- ALTER TABLE mg_app MODIFY COLUMN update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '修改时间';
-- 其余表同理，按 ② 的结果逐条放开。

-- ---------------------------------------------------------------------------
-- 修复 C（**根治，且不在 SQL 里**）：确保 MetaObjectHandler 被装配。
--
-- 1. 内网合并版引入 MybatisWiringSelfCheck（已改为按类型注入，不依赖 bean 名）。
--    装配正确时它只在启动打一行日志：
--        [MyBatis自检] 装配校验通过: MetaObjectHandler=AuditFieldMetaObjectHandler, ...
--    装配缺失时直接启动失败，并在异常信息里写明后果与修复位置。
-- 2. 启动后确认日志里有上面那行。**没有这行就说明自检类没被扫描到**，
--    检查它是否在 @ComponentScan 覆盖的包路径下。
-- 3. 确认后，在管理面新建一个接入系统，再查一次第一部分的 ①，
--    确认新增行的 create_time / update_time 有值。
-- ---------------------------------------------------------------------------


-- ============================================================================
-- 校验：修复后重跑第一部分 ①，四列"为空"应全为 0
-- ============================================================================
