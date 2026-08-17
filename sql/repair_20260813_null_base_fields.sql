-- ============================================================================
-- repair_20260813_null_base_fields.sql
--
-- 【数据修复脚本，非 DDL】修正基础字段为 NULL 的存量行，并清理由此产生的重复行。
--
-- ---------------------------------------------------------------------------
-- 问题现象
-- ---------------------------------------------------------------------------
--   1. 新增模型/接入系统/凭证后接口返回成功，但列表里查不到；
--   2. 直接查库能看到行，但 is_del、create_time、update_time 全是 NULL；
--   3. 模型的上游鉴权 key 看起来"没保存成功"；
--   4. 同一个 model_id 在 mg_model_credential 里堆了好几行。
--
-- ---------------------------------------------------------------------------
-- 根因（一条链，解释上面全部四点）
-- ---------------------------------------------------------------------------
--   TdsqlMybatisPlusConfig 自定义了 SqlSessionFactory，MybatisPlusAutoConfiguration 因此
--   整体退避，容器里的 AuditFieldMetaObjectHandler **没有被挂进 GlobalConfig**，自动填充失效。
--     ↓
--   is_del 标了 @TableField(fill = INSERT)，MyBatis-Plus 仍把它写进 INSERT，值为 NULL，
--   **显式 NULL 覆盖掉建表时的 DEFAULT '0'**。
--     ↓
--   @TableLogic(value='0') 让每条查询都带 WHERE is_del='0'，而 SQL 里 '0' 匹配不到 NULL，
--   于是这些行永久不可见（现象 1、2、3）。
--     ↓
--   ModelService.applyCredentialUpdate 用 selectOne 找现有凭证，同样被加上 is_del='0'，
--   找不到 → 走 INSERT 分支 → **每编辑一次模型就新插一行凭证**（现象 4）。
--   而 MySQL 唯一索引**不约束 NULL**（多个 NULL 可共存），所以一直没报错。
--
--   代码修复见同版本：TdsqlMybatisPlusConfig 已挂 GlobalConfig.setMetaObjectHandler；
--   6 个实体的 isDel 字段另加了默认值 "0" 作为不依赖框架的兜底；
--   MybatisWiringSelfCheck 会在启动时断言装配完整，缺失即启动失败。
--
-- ---------------------------------------------------------------------------
-- ⚠️ 为什么必须先去重再补 NULL
-- ---------------------------------------------------------------------------
--   四张表的唯一键含 is_del：
--     mg_model            uk_model_id  (model_id, is_del)
--     mg_model_credential uk_model_id  (model_id, is_del)
--     mg_app              uk_app_id    (app_id,   is_del)
--     mg_app_model_auth   uk_app_model (app_id, model_id, is_del)
--   这意味着**每个业务键最多只能有一行 is_del='0'**。若某业务键有 N 行 NULL，
--   直接 UPDATE 成 '0' 必然报 Duplicate entry（已实际发生）。
--
-- ⚠️ 执行顺序：**先发布代码，再跑本脚本**。否则修好的行会被随后写入的新 NULL 行继续污染。
--
-- 执行：mysql -h.. -P.. -u.. -p 库名 < sql/repair_20260813_null_base_fields.sql
-- ============================================================================


-- ============================================================================
-- 第一步：查看影响面（只读，先看清楚再往下执行）
-- ============================================================================

-- 1.1 各表 is_del 为 NULL 的行数
SELECT 'mg_model'            AS table_name, COUNT(*) AS null_is_del_rows FROM mg_model            WHERE is_del IS NULL
UNION ALL SELECT 'mg_model_credential', COUNT(*) FROM mg_model_credential WHERE is_del IS NULL
UNION ALL SELECT 'mg_app',              COUNT(*) FROM mg_app              WHERE is_del IS NULL
UNION ALL SELECT 'mg_app_credential',   COUNT(*) FROM mg_app_credential   WHERE is_del IS NULL
UNION ALL SELECT 'mg_app_model_auth',   COUNT(*) FROM mg_app_model_auth   WHERE is_del IS NULL
UNION ALL SELECT 'mg_access_rule',      COUNT(*) FROM mg_access_rule      WHERE is_del IS NULL;

-- 1.2 会撞唯一键的重复组（补 NULL 之前必须先解决这些）
--     保留策略是 last-write-wins：每组只留 id 最大的那行（即最后一次编辑的结果）
SELECT 'mg_model_credential' AS table_name, model_id AS biz_key, COUNT(*) AS dup_rows,
       MAX(id) AS keep_id, GROUP_CONCAT(id ORDER BY id) AS all_ids
  FROM mg_model_credential WHERE is_del IS NULL GROUP BY model_id HAVING COUNT(*) > 1
UNION ALL
SELECT 'mg_model', model_id, COUNT(*), MAX(id), GROUP_CONCAT(id ORDER BY id)
  FROM mg_model WHERE is_del IS NULL GROUP BY model_id HAVING COUNT(*) > 1
UNION ALL
SELECT 'mg_app', app_id, COUNT(*), MAX(id), GROUP_CONCAT(id ORDER BY id)
  FROM mg_app WHERE is_del IS NULL GROUP BY app_id HAVING COUNT(*) > 1
UNION ALL
SELECT 'mg_app_model_auth', CONCAT(app_id, '/', model_id), COUNT(*), MAX(id), GROUP_CONCAT(id ORDER BY id)
  FROM mg_app_model_auth WHERE is_del IS NULL GROUP BY app_id, model_id HAVING COUNT(*) > 1;

-- 1.3 ⚠️ 混合组：某业务键**既有 NULL 行又已有 is_del='0' 行**。
--     这种情况脚本**不自动处理**——两行都可能是有效配置，机器无法判断该留哪个。
--     若下面查出结果，请人工确认后手动处理，再继续执行第二步。
SELECT 'mg_model_credential' AS table_name, a.model_id AS biz_key,
       (SELECT GROUP_CONCAT(id) FROM mg_model_credential x WHERE x.model_id=a.model_id AND x.is_del IS NULL) AS null_ids,
       (SELECT GROUP_CONCAT(id) FROM mg_model_credential y WHERE y.model_id=a.model_id AND y.is_del='0')     AS zero_ids
  FROM (SELECT DISTINCT model_id FROM mg_model_credential WHERE is_del IS NULL) a
 WHERE EXISTS (SELECT 1 FROM mg_model_credential z WHERE z.model_id=a.model_id AND z.is_del='0')
UNION ALL
SELECT 'mg_model', a.model_id,
       (SELECT GROUP_CONCAT(id) FROM mg_model x WHERE x.model_id=a.model_id AND x.is_del IS NULL),
       (SELECT GROUP_CONCAT(id) FROM mg_model y WHERE y.model_id=a.model_id AND y.is_del='0')
  FROM (SELECT DISTINCT model_id FROM mg_model WHERE is_del IS NULL) a
 WHERE EXISTS (SELECT 1 FROM mg_model z WHERE z.model_id=a.model_id AND z.is_del='0')
UNION ALL
SELECT 'mg_app', a.app_id,
       (SELECT GROUP_CONCAT(id) FROM mg_app x WHERE x.app_id=a.app_id AND x.is_del IS NULL),
       (SELECT GROUP_CONCAT(id) FROM mg_app y WHERE y.app_id=a.app_id AND y.is_del='0')
  FROM (SELECT DISTINCT app_id FROM mg_app WHERE is_del IS NULL) a
 WHERE EXISTS (SELECT 1 FROM mg_app z WHERE z.app_id=a.app_id AND z.is_del='0')
UNION ALL
SELECT 'mg_app_model_auth', CONCAT(a.app_id,'/',a.model_id),
       (SELECT GROUP_CONCAT(id) FROM mg_app_model_auth x WHERE x.app_id=a.app_id AND x.model_id=a.model_id AND x.is_del IS NULL),
       (SELECT GROUP_CONCAT(id) FROM mg_app_model_auth y WHERE y.app_id=a.app_id AND y.model_id=a.model_id AND y.is_del='0')
  FROM (SELECT DISTINCT app_id, model_id FROM mg_app_model_auth WHERE is_del IS NULL) a
 WHERE EXISTS (SELECT 1 FROM mg_app_model_auth z WHERE z.app_id=a.app_id AND z.model_id=a.model_id AND z.is_del='0');


-- ============================================================================
-- 第二步：去重（保留每组 id 最大的一行 = 最后一次编辑的结果）
--
-- ⚠️ 这里是**物理删除**，与 05 §8「禁止物理删除管理表数据」相悖，属**明确的例外**：
--    这些是 bug 产生的重复行而非业务数据，且唯一键 (业务键, is_del) 物理上容不下多行，
--    标记 is_del='1' 同样会撞键，除物理删除外无解。
--    删除前第一步的 1.2 已把每组的全部 id 与保留 id 打印出来，可留档备查。
--
-- ⚠️ 只删 is_del IS NULL 的行，任何 is_del 已有值的行都不碰。
-- ============================================================================

DELETE c FROM mg_model_credential c
  JOIN (SELECT model_id, MAX(id) AS keep_id FROM mg_model_credential
         WHERE is_del IS NULL GROUP BY model_id HAVING COUNT(*) > 1) t
    ON c.model_id = t.model_id AND c.id < t.keep_id
 WHERE c.is_del IS NULL;

DELETE m FROM mg_model m
  JOIN (SELECT model_id, MAX(id) AS keep_id FROM mg_model
         WHERE is_del IS NULL GROUP BY model_id HAVING COUNT(*) > 1) t
    ON m.model_id = t.model_id AND m.id < t.keep_id
 WHERE m.is_del IS NULL;

DELETE a FROM mg_app a
  JOIN (SELECT app_id, MAX(id) AS keep_id FROM mg_app
         WHERE is_del IS NULL GROUP BY app_id HAVING COUNT(*) > 1) t
    ON a.app_id = t.app_id AND a.id < t.keep_id
 WHERE a.is_del IS NULL;

DELETE u FROM mg_app_model_auth u
  JOIN (SELECT app_id, model_id, MAX(id) AS keep_id FROM mg_app_model_auth
         WHERE is_del IS NULL GROUP BY app_id, model_id HAVING COUNT(*) > 1) t
    ON u.app_id = t.app_id AND u.model_id = t.model_id AND u.id < t.keep_id
 WHERE u.is_del IS NULL;


-- ============================================================================
-- 第三步：补齐 NULL 基础字段
--   creater / updater 保持 NULL：这些行确实无法追溯是谁建的，
--   填假值反而污染审计（OperatorProvider 当前也返回 null，见 11 §2 待办）。
-- ============================================================================

UPDATE mg_model
   SET is_del      = COALESCE(is_del, '0'),
       create_time = COALESCE(create_time, update_time, NOW()),
       update_time = COALESCE(update_time, create_time, NOW())
 WHERE is_del IS NULL OR create_time IS NULL OR update_time IS NULL;

UPDATE mg_model_credential
   SET is_del      = COALESCE(is_del, '0'),
       create_time = COALESCE(create_time, update_time, NOW()),
       update_time = COALESCE(update_time, create_time, NOW())
 WHERE is_del IS NULL OR create_time IS NULL OR update_time IS NULL;

UPDATE mg_app
   SET is_del      = COALESCE(is_del, '0'),
       create_time = COALESCE(create_time, update_time, NOW()),
       update_time = COALESCE(update_time, create_time, NOW())
 WHERE is_del IS NULL OR create_time IS NULL OR update_time IS NULL;

UPDATE mg_app_credential
   SET is_del      = COALESCE(is_del, '0'),
       create_time = COALESCE(create_time, update_time, NOW()),
       update_time = COALESCE(update_time, create_time, NOW())
 WHERE is_del IS NULL OR create_time IS NULL OR update_time IS NULL;

UPDATE mg_app_model_auth
   SET is_del      = COALESCE(is_del, '0'),
       create_time = COALESCE(create_time, update_time, NOW()),
       update_time = COALESCE(update_time, create_time, NOW())
 WHERE is_del IS NULL OR create_time IS NULL OR update_time IS NULL;

UPDATE mg_access_rule
   SET is_del      = COALESCE(is_del, '0'),
       create_time = COALESCE(create_time, update_time, NOW()),
       update_time = COALESCE(update_time, create_time, NOW())
 WHERE is_del IS NULL OR create_time IS NULL OR update_time IS NULL;


-- ============================================================================
-- 第四步：校验（remaining_null 应全为 0）
-- ============================================================================
SELECT 'mg_model'            AS table_name, COUNT(*) AS remaining_null FROM mg_model            WHERE is_del IS NULL
UNION ALL SELECT 'mg_model_credential', COUNT(*) FROM mg_model_credential WHERE is_del IS NULL
UNION ALL SELECT 'mg_app',              COUNT(*) FROM mg_app              WHERE is_del IS NULL
UNION ALL SELECT 'mg_app_credential',   COUNT(*) FROM mg_app_credential   WHERE is_del IS NULL
UNION ALL SELECT 'mg_app_model_auth',   COUNT(*) FROM mg_app_model_auth   WHERE is_del IS NULL
UNION ALL SELECT 'mg_access_rule',      COUNT(*) FROM mg_access_rule      WHERE is_del IS NULL;

-- 校验每个 model_id 只剩一行有效凭证
SELECT model_id, COUNT(*) AS active_rows FROM mg_model_credential
 WHERE is_del = '0' GROUP BY model_id HAVING COUNT(*) > 1;

-- ⚠️ 不在修复范围内的表：
--    mg_gateway_config  —— 无 is_del 列（唯一不做逻辑删除的管理表，设计如此）
--    mg_audit_log / mg_request_payload —— 高频追加表，无基础字段

-- ============================================================================
-- 无回滚脚本
-- 第三步只把 NULL 补成默认值，不改动任何非 NULL 数据；
-- 第二步的物理删除不可逆，故第一步的 1.2 会先把被删 id 全部打印出来，执行前请留档。
-- ============================================================================
