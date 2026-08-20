-- ============================================================================
-- upgrade_20260819_sha256_utf8_migration.sql
--
-- 配合代码变更：CryptoService.sha256Hex 的字符集由「平台默认」固定为 UTF-8。
--
-- 【为什么改】
--   原实现是 input.getBytes()，取平台默认字符集：Linux 通常 UTF-8、Windows 是 GBK。
--   同一个密钥在两台机器上会算出**不同的哈希**，表现为「凭证在某台机器上突然认不出来」，
--   且只在密钥含非 ASCII 字符时才出现，极难复现定位。属于必须消除的隐患。
--
-- 【影响面有多大】
--   只有被哈希的字符串**含非 ASCII 字符**时，新旧结果才会不同。
--     · ak_sk 类型：SK = "SK_" + URL-safe Base64  → 纯 ASCII，**完全不受影响**
--     · api_key 类型：apiKey = "mg-" + appId + "-" + URL-safe Base64
--       → 唯一变量是 appId，而 appId 是建接入系统时用户填的
--   因此：**只有「appId 含非 ASCII 字符」的 api_key 凭证会失效**，需重新生成。
--
-- 【执行顺序】
--   1. 先跑下面的诊断，看看有没有受影响的凭证
--   2. 有的话，在管理面把这些凭证**重新生成**（旧的注定失效，明文已不可得）
--   3. 再部署新版本
--   顺序反了会有一段时间这些凭证认证失败。若诊断结果为 0 条，可直接部署。
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 诊断：找出受影响的凭证（只读，不改数据）
--
-- 判据：appId 含非 ASCII 字符。用 CONVERT(... USING ascii) 回转后比对——
-- 能无损转成 ascii 说明是纯 ASCII，转不了或转完不等说明含非 ASCII。
-- ----------------------------------------------------------------------------
SELECT  c.id            AS 凭证ID,
        c.app_id        AS 接入系统,
        c.cred_name     AS 凭证名称,
        c.cred_type     AS 凭证类型,
        c.enabled       AS 是否启用,
        '需重新生成'    AS 处理方式
  FROM  mg_app_credential c
 WHERE  c.cred_type = 'api_key'
   AND  c.is_del = '0'
   AND  CONVERT(c.app_id USING ascii) <> c.app_id;

-- 对照：确认 ak_sk 类型不受影响（SK 是纯 ASCII，与 appId 无关）
SELECT  COUNT(*) AS aksk凭证数_不受影响
  FROM  mg_app_credential
 WHERE  cred_type = 'ak_sk' AND is_del = '0';

-- 全量分布，便于确认影响比例
SELECT  cred_type                                   AS 凭证类型,
        COUNT(*)                                    AS 总数,
        SUM(CONVERT(app_id USING ascii) <> app_id)  AS 其中appId含非ASCII
  FROM  mg_app_credential
 WHERE  is_del = '0'
 GROUP  BY cred_type;


-- ============================================================================
-- 修复
--
-- ⚠️ 没有 SQL 层面的修复手段：secret_hash 是单向哈希，旧明文无法从库里恢复，
--    也就无法用新字符集重算。**只能在管理面重新生成凭证**，并把新密钥发给接入方。
--
-- 下面这条仅用于「先禁用受影响凭证、避免它们在切换期间静默失败」，按需使用。
-- 默认注释掉。
-- ============================================================================
-- UPDATE mg_app_credential
--    SET enabled = 0,
--        remark  = CONCAT(IFNULL(remark, ''), ' [2026-08-19 sha256 字符集迁移：appId 含非 ASCII，需重新生成]')
--  WHERE cred_type = 'api_key'
--    AND is_del = '0'
--    AND CONVERT(app_id USING ascii) <> app_id;


-- ============================================================================
-- 校验：部署后确认受影响凭证已全部处理
-- ============================================================================
-- 重新生成后，上面第一条诊断应返回 0 行（或返回的都是已重新生成过的新凭证）。
-- 另外可用管理面的「接入示例 → 测试连通」对每个 api_key 凭证实测一次。
