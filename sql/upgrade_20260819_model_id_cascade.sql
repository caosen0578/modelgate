-- ============================================================================
-- upgrade_20260819_model_id_cascade.sql
--
-- 目的：让 mg_model.model_id 可以被修改。
--
-- 【为什么现在改不了】
--   两张表用外键引用 mg_model.model_id，且都是 ON UPDATE NO ACTION：
--       fk_modelcred_model   mg_model_credential.model_id
--       fk_auth_model        mg_app_model_auth.model_id
--   在有子行的情况下 UPDATE 父表的 model_id 会被外键直接拒绝。
--   本脚本把这两个外键改成 ON UPDATE CASCADE —— 父表改名时，子表自动跟着改。
--
--   ON DELETE 保持 NO ACTION 不变：删模型时仍应先处理凭证与授权，
--   不能因为改了 UPDATE 规则就顺手把删除也放开，那是另一回事、风险大得多。
--
-- 【改名会连带什么，执行前务必知晓】
--   1. **旧 ID 会失效**：当前实例清缓存后即返回 404；多副本其他实例当前按最长 60 秒 TTL 收敛。
--      改名前必须先通知所有接入方，并在低流量窗口操作。
--   2. **历史数据保留旧 ID**：mg_audit_log / mg_request_payload 没有外键、不会级联，
--      历史行仍是旧名字。这是**有意的**——那些调用当时确实是打到旧 ID 的，改掉就是篡改历史。
--      但要知道后果：用量统计会按新旧两个名字裂成两行，需要人工合并口径。
--   3. 凭证与授权会自动跟随（本脚本的作用），不需要手工处理。
--   4. Redis 日/月配额 key 含 model ID，改名后新 ID 从 0 重新累计；不在线搬迁，避免在途请求与
--      异步 Token 回写造成漏计/重复计数。严格配额场景优先新建模型并逐步迁移，不直接改名。
--
-- 【替代方案：多数情况下你不需要改名】
--   如果目的只是「对外用一个不同的名字」，用 upstream_model_id 更好：
--   新建一条 mg_model 用新的对外 ID，upstream_model_id 填上游真实 ID，
--   旧的那条保持不动。这样旧调用方不受影响，历史统计也不裂。
--   见 upgrade_20260819_upstream_model_id.sql。
--   **真正需要改名的场景是「当初 ID 就建错了、且还没人用」。**
--
-- 执行：mysql -h.. -P.. -u.. -p 库名 < sql/upgrade_20260819_model_id_cascade.sql
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 执行前先看一眼当前规则（应为 NO ACTION）
-- ----------------------------------------------------------------------------
SELECT k.CONSTRAINT_NAME AS 约束名, k.TABLE_NAME AS 子表, r.UPDATE_RULE AS 更新规则
  FROM information_schema.KEY_COLUMN_USAGE k
  JOIN information_schema.REFERENTIAL_CONSTRAINTS r
    ON r.CONSTRAINT_NAME = k.CONSTRAINT_NAME AND r.CONSTRAINT_SCHEMA = k.TABLE_SCHEMA
 WHERE k.TABLE_SCHEMA = DATABASE()
   AND k.REFERENCED_TABLE_NAME = 'mg_model'
   AND k.REFERENCED_COLUMN_NAME = 'model_id';


-- ----------------------------------------------------------------------------
-- 重建外键：加上 ON UPDATE CASCADE
--
-- MySQL 不支持直接修改外键规则，只能 DROP + ADD；同一张表合并成一条 ALTER，
-- 避免两条独立 DDL 之间出现无外键窗口。两张表的 DDL 仍不是跨表原子操作，且可能等待/持有
-- metadata lock，生产必须在维护或低流量窗口执行，并暂停模型管理写操作。
-- ----------------------------------------------------------------------------
ALTER TABLE mg_model_credential
    DROP FOREIGN KEY fk_modelcred_model,
    ADD CONSTRAINT fk_modelcred_model
    FOREIGN KEY (model_id) REFERENCES mg_model (model_id)
    ON UPDATE CASCADE;

ALTER TABLE mg_app_model_auth
    DROP FOREIGN KEY fk_auth_model,
    ADD CONSTRAINT fk_auth_model
    FOREIGN KEY (model_id) REFERENCES mg_model (model_id)
    ON UPDATE CASCADE;


-- ----------------------------------------------------------------------------
-- 校验：两条都应变成 CASCADE
-- ----------------------------------------------------------------------------
SELECT k.CONSTRAINT_NAME AS 约束名, k.TABLE_NAME AS 子表, r.UPDATE_RULE AS 更新规则
  FROM information_schema.KEY_COLUMN_USAGE k
  JOIN information_schema.REFERENTIAL_CONSTRAINTS r
    ON r.CONSTRAINT_NAME = k.CONSTRAINT_NAME AND r.CONSTRAINT_SCHEMA = k.TABLE_SCHEMA
 WHERE k.TABLE_SCHEMA = DATABASE()
   AND k.REFERENCED_TABLE_NAME = 'mg_model'
   AND k.REFERENCED_COLUMN_NAME = 'model_id';


-- ============================================================================
-- 回滚
-- ============================================================================
-- ALTER TABLE mg_model_credential
--     DROP FOREIGN KEY fk_modelcred_model,
--     ADD CONSTRAINT fk_modelcred_model
--     FOREIGN KEY (model_id) REFERENCES mg_model (model_id);
-- ALTER TABLE mg_app_model_auth
--     DROP FOREIGN KEY fk_auth_model,
--     ADD CONSTRAINT fk_auth_model
--     FOREIGN KEY (model_id) REFERENCES mg_model (model_id);
