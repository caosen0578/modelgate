-- ============================================================================
-- 增量升级：mg_model / mg_model_credential / mg_app 唯一键纳入 is_del
--
-- 背景
--   逻辑删除下软删的行仍留在表中，继续占用唯一键。因此删除一个模型后，用同样的
--   model_id 重建会撞 Duplicate key —— 表现为「删掉的模型 ID 再也用不了」。
--   mg_app_model_auth 早已用 (app_id, model_id, is_del) 解决了同一问题，本次将同一
--   做法推广到其余三处。
--
-- 变更清单
--   mg_model             uk_model_id (model_id)  -> (model_id, is_del)
--   mg_model_credential  uk_model_id (model_id)  -> (model_id, is_del)
--   mg_app               uk_app_id   (app_id)    -> (app_id,   is_del)
--
--   ⚠️ mg_model_credential 必须一起改：ModelService.delete() 会逻辑删除凭证行，
--      该行仍占 uk_model_id。只改 mg_model 的话，同名重建时模型能插入、凭证仍冲突，
--      等于没修。
--
-- 为什么先加后删
--   子表外键 fk_modelcred_model / fk_auth_model 引用 mg_model(model_id)，
--   fk_appcred_app / fk_auth_app / fk_rule_app 引用 mg_app(app_id)。
--   InnoDB 要求被引用列存在以其为最左前缀的索引，直接 DROP 旧唯一键会报 errno 150。
--   故先建新索引（model_id 仍是最左前缀，外键要求依然满足），再删旧索引。
--
-- 代码影响：无。@TableLogic 行为不变，Java 侧无需改动。
--
-- 执行环境：在目标库（如 cbdb）下直接执行，不含 CREATE DATABASE / USE
-- 幂等性：不幂等。重复执行会因索引已存在/已不存在而报错，属预期。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 前置检查：确认不存在会阻断建索引的重复数据
-- 旧唯一键保证了 model_id / app_id 全表唯一，加入 is_del 只会放宽约束，
-- 正常情况下应全部返回 0 行。若有输出，说明数据已被绕过约束写脏，需先人工处理。
-- ---------------------------------------------------------------------------
SELECT model_id, is_del, COUNT(*) c FROM mg_model
    GROUP BY model_id, is_del HAVING c > 1;
SELECT model_id, is_del, COUNT(*) c FROM mg_model_credential
    GROUP BY model_id, is_del HAVING c > 1;
SELECT app_id, is_del, COUNT(*) c FROM mg_app
    GROUP BY app_id, is_del HAVING c > 1;

-- ---------------------------------------------------------------------------
-- 1. mg_model
-- ---------------------------------------------------------------------------
ALTER TABLE mg_model     ADD UNIQUE KEY uk_model_id_new (model_id, is_del);
ALTER TABLE mg_model     DROP INDEX uk_model_id;
ALTER TABLE mg_model     RENAME INDEX uk_model_id_new TO uk_model_id;

-- ---------------------------------------------------------------------------
-- 2. mg_model_credential
-- ---------------------------------------------------------------------------
ALTER TABLE mg_model_credential ADD UNIQUE KEY uk_model_id_new (model_id, is_del);
ALTER TABLE mg_model_credential DROP INDEX uk_model_id;
ALTER TABLE mg_model_credential RENAME INDEX uk_model_id_new TO uk_model_id;

-- ---------------------------------------------------------------------------
-- 3. mg_app
-- ---------------------------------------------------------------------------
ALTER TABLE mg_app       ADD UNIQUE KEY uk_app_id_new (app_id, is_del);
ALTER TABLE mg_app       DROP INDEX uk_app_id;
ALTER TABLE mg_app       RENAME INDEX uk_app_id_new TO uk_app_id;

-- ============================================================================
-- 遗留语义（本次不改，知悉即可）
--   唯一键是 (业务键, is_del)，而 is_del 只有 '0'/'1' 两个值，因此**同一个 model_id
--   最多只能存在一条软删行**。同名删除→重建→再删除时，第二次删除会因已有一条
--   is_del='1' 的同名行而撞唯一键。
--   实际影响很小（要求同一 ID 反复删建两轮以上），且 mg_app_model_auth 早已是同样
--   的语义。彻底解决需把 is_del 换成删除时间戳或雪花值，属更大改动，另行评估。
-- ============================================================================

-- ============================================================================
-- 回滚脚本（仅在需要退回旧结构时执行；表中若已存在同名软删行会失败，需先清理）
-- ----------------------------------------------------------------------------
-- ALTER TABLE mg_model            ADD UNIQUE KEY uk_model_id_old (model_id);
-- ALTER TABLE mg_model            DROP INDEX uk_model_id;
-- ALTER TABLE mg_model            RENAME INDEX uk_model_id_old TO uk_model_id;
-- ALTER TABLE mg_model_credential ADD UNIQUE KEY uk_model_id_old (model_id);
-- ALTER TABLE mg_model_credential DROP INDEX uk_model_id;
-- ALTER TABLE mg_model_credential RENAME INDEX uk_model_id_old TO uk_model_id;
-- ALTER TABLE mg_app              ADD UNIQUE KEY uk_app_id_old (app_id);
-- ALTER TABLE mg_app              DROP INDEX uk_app_id;
-- ALTER TABLE mg_app              RENAME INDEX uk_app_id_old TO uk_app_id;
-- ============================================================================

-- 验证：三张表的唯一键均应含 is_del，且外键仍然有效
-- SHOW INDEX FROM mg_model            WHERE Key_name = 'uk_model_id';
-- SHOW INDEX FROM mg_model_credential WHERE Key_name = 'uk_model_id';
-- SHOW INDEX FROM mg_app              WHERE Key_name = 'uk_app_id';
-- SELECT TABLE_NAME, CONSTRAINT_NAME FROM information_schema.REFERENTIAL_CONSTRAINTS
--     WHERE CONSTRAINT_SCHEMA = DATABASE();
