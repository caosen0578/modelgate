-- ============================================================================
-- ModelGate 增量脚本 · 2026-09-10 · 编码由 base64 全面改为 hex
-- ----------------------------------------------------------------------------
-- 背景：内网合规要求禁用 base64。项目里四处运行时用到 base64 已全部改为
--   小写十六进制（Base16）：
--     1) AES-256-GCM 密文（CryptoService.encrypt/decrypt）
--     2) 主密钥 modelgate.crypto.key（44 位 base64 → 64 位 hex）
--     3) AKSK 签名（Authorization: AKSK {ak}:{ts}:{hex}）
--     4) 随机密钥材料（CredentialService.randomHex）
--
-- 本脚本只做一件事：**放宽两个密文列的宽度**。
--   hex 的膨胀率是 2.0x（base64 是 1.33x），同样的 VARCHAR(512)：
--     · base64 能装下 384 字节密文 → 上游 SK 明文上限约 356 字符
--     · hex   只能装下 256 字节密文 → 上游 SK 明文上限约 228 字符
--   （密文 = 12 字节 IV + 明文 + 16 字节 GCM tag）
--   228 字符对现实中的上游密钥（OpenAI ~51、Azure 32、百炼 ~35）绰绰有余，
--   但没必要为了省几百字节把上限压在这个位置——扩到 1024（明文上限约 484 字符）。
--
-- ⚠️ 存量密文无法自动迁移
--   改造前写入的 base64 密文，用新代码读会报
--   「凭证密文不是合法的十六进制串，数据已损坏或为 hex 改造前的旧格式」。
--   密文本身没坏，但本脚本刻意**不做**转码：现在项目处于测试阶段，库里只有联调数据，
--   重新生成凭证比写一段一次性转码逻辑更干净，也不会在代码里留下双格式兼容分支。
--   如果某套环境确实有不能丢的存量凭证，先停在这一步找我，不要直接跑。
--
--   需要重新生成的对象（升级后由管理面重新录入/签发）：
--     · mg_app_credential     —— 所有 ak_sk 凭证（secret_encrypted 解不开则 AKSK 验签必失败）
--     · mg_model_credential   —— 所有 secret_key 非空的上游凭证
--     · mg_service_credential —— 所有 secret_key / extra_headers 非空的上游凭证
--   注：api_key 类型凭证的鉴权只依赖 secret_hash（SHA-256 hex，本次未变），
--       不重新生成也能继续验过；但管理面的「查看密钥」会解不开旧密文。
--
-- 另需手工执行（不在本脚本内）：
--   把 application.yml 的 modelgate.crypto.key 换成 64 位 hex，
--   生成命令 openssl rand -hex 32，详见 spec/modelgate/10-部署与运维手册 §2.1。
--
-- 脚本可重复执行（改列宽走存在性判断，已是 1024 则跳过）。
--
-- 执行方式：
--   mysql -h<host> -P<port> -u<user> -p <库名> < sql/upgrade_20260910_hex_encoding.sql
-- ============================================================================

DROP PROCEDURE IF EXISTS mg_widen_column_if_narrower;
DELIMITER $$
CREATE PROCEDURE mg_widen_column_if_narrower(
    IN p_table  VARCHAR(64),
    IN p_column VARCHAR(64),
    IN p_len    INT,
    IN p_ddl    TEXT)
BEGIN
    IF EXISTS (SELECT 1 FROM information_schema.COLUMNS
               WHERE TABLE_SCHEMA = DATABASE()
                 AND TABLE_NAME   = p_table
                 AND COLUMN_NAME  = p_column
                 AND CHARACTER_MAXIMUM_LENGTH < p_len) THEN
        SET @s = CONCAT('ALTER TABLE `', p_table, '` MODIFY COLUMN ', p_ddl);
        PREPARE stmt FROM @s; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END$$
DELIMITER ;

-- 上游模型凭证：SK 密文
CALL mg_widen_column_if_narrower('mg_model_credential', 'secret_key', 1024,
    "secret_key VARCHAR(1024) COMMENT 'SK/密钥，加密存储（AES-256-GCM，密文为小写 hex）'");

-- 上游服务凭证：SK 密文
CALL mg_widen_column_if_narrower('mg_service_credential', 'secret_key', 1024,
    "secret_key VARCHAR(1024) COMMENT 'SK/密钥，加密存储（AES-256-GCM，密文为小写 hex）'");

DROP PROCEDURE IF EXISTS mg_widen_column_if_narrower;

-- ----------------------------------------------------------------------------
-- 列注释同步（宽度没变，只把「密文是 hex」写进注释，便于后来人对账）
-- ----------------------------------------------------------------------------
ALTER TABLE mg_app_credential
    MODIFY COLUMN secret_hash      VARCHAR(128) COMMENT 'SHA-256(api_key) 或 SHA-256(sk)，64 位小写 hex，用于鉴权比对',
    MODIFY COLUMN secret_encrypted VARCHAR(512) COMMENT '加密后的 SK（AES-256-GCM，密文为小写 hex），仅 ak_sk 类型使用';

ALTER TABLE mg_service_credential
    MODIFY COLUMN extra_headers TEXT COMMENT '附加上游请求头（JSON 对象，加密存储，密文为小写 hex）';

-- ----------------------------------------------------------------------------
-- 升级后自查：以下三条应当**查不出任何行**。
-- 有行说明该行密文仍是改造前的 base64（hex 只含 0-9a-f，base64 必然带
-- 大写字母 / '+' / '/' / '=' 中的至少一种），需要重新生成对应凭证。
--
-- 注意 utf8mb4_unicode_ci 是不区分大小写的排序规则，直接 REGEXP 匹配大写字母会误判，
-- 所以统一用 BINARY 做区分大小写比较。
-- ----------------------------------------------------------------------------
-- SELECT id, app_id, cred_name FROM mg_app_credential
--  WHERE secret_encrypted IS NOT NULL AND secret_encrypted <> ''
--    AND BINARY secret_encrypted NOT REGEXP '^[0-9a-f]+$';
--
-- SELECT id, model_id FROM mg_model_credential
--  WHERE secret_key IS NOT NULL AND secret_key <> ''
--    AND BINARY secret_key NOT REGEXP '^[0-9a-f]+$';
--
-- SELECT id, service_id FROM mg_service_credential
--  WHERE (secret_key    IS NOT NULL AND secret_key    <> '' AND BINARY secret_key    NOT REGEXP '^[0-9a-f]+$')
--     OR (extra_headers IS NOT NULL AND extra_headers <> '' AND BINARY extra_headers NOT REGEXP '^[0-9a-f]+$');
