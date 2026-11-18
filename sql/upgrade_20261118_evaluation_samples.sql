-- 已执行 upgrade_20261117_model_evaluation.sql 的存量库执行一次。
-- 自建样本没有源报文 ID；旧样本默认未分级，原报文关联继续保留。
ALTER TABLE mg_eval_case
    MODIFY COLUMN payload_id BIGINT NULL,
    ADD COLUMN difficulty VARCHAR(16) NOT NULL DEFAULT 'UNRATED' COMMENT 'UNRATED/EASY/MEDIUM/HARD',
    ADD COLUMN title VARCHAR(128) NULL COMMENT '自建样本标题';
