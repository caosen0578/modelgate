-- ============================================================================
-- upgrade_20260819_upstream_model_id.sql
--
-- 新增「上游真实模型 ID」，让**对外暴露的模型 ID** 与**转发给上游的模型 ID** 解耦。
--
-- 【解决什么问题】
--   同一个真实模型部署在两个平台（两个域名），但平台以「模型 ID 重复」为由拒绝新增
--   第二个模型地址。于是需要网关对外暴露不同的 ID，转发时再改回真实 ID。
--
-- 【与 force_params 的关系】
--   接线 force_params 之后，写 {"model":"真实ID"} 也能达到同样效果。
--   但那是把「路由身份」塞进「请求参数」，语义不清、页面上找不到、无法校验、
--   模型列表也展示不出映射关系。故单列一个字段，force_params 回归它本来的用途
--   （temperature / max_tokens 这类调用参数）。
--
--   ⚠️ 两处都设了 model 时，**upstream_model_id 优先**——它是专为此设计的字段。
--
-- 【性能】
--   留空（绝大多数模型）→ 完全零开销，一个字节都不动。
--   填了且模型没有其它参数配置 → 走**字节级定点替换**（一次 arraycopy），不解析不建树。
--   填了且模型还配了 default/force/mapping → 复用那次已经付过的解析，改名不额外收费。
--
-- 执行：mysql -h.. -P.. -u.. -p 库名 < sql/upgrade_20260819_upstream_model_id.sql
-- ============================================================================

ALTER TABLE mg_model
    ADD COLUMN upstream_model_id VARCHAR(64) DEFAULT NULL
    COMMENT '上游真实模型 ID。留空=直接透传 model_id；填了=转发前把请求体里的 model 改成它。用于对外 ID 与上游 ID 解耦'
    AFTER model_id;


-- ----------------------------------------------------------------------------
-- 校验
-- ----------------------------------------------------------------------------
SELECT COLUMN_NAME, COLUMN_TYPE, IS_NULLABLE, COLUMN_COMMENT
  FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND TABLE_NAME   = 'mg_model'
   AND COLUMN_NAME  = 'upstream_model_id';


-- ============================================================================
-- 用法示例：同一真实模型挂两个对外 ID（对应两个平台/两个域名）
-- ============================================================================
-- 第一条：对外 ID 就是真实 ID，upstream_model_id 留空
--   model_id = 'deepseek-ai/DeepSeek-V3'
--   upstream_url = 'https://平台A域名'
--   upstream_model_id = NULL
--
-- 第二条：对外换个名字，转发时改回真实 ID
--   model_id = 'deepseek-v3-b'
--   upstream_url = 'https://平台B域名'
--   upstream_model_id = 'deepseek-ai/DeepSeek-V3'
--
-- 要点：
--   · 调用方用 deepseek-v3-b 调，上游收到的是 deepseek-ai/DeepSeek-V3
--   · 两条记录各有独立的凭证、授权、配额、限流——这是按 model_id 行独立的
--   · 审计与用量统计按**对外 ID** 记，账不会混
--   · 响应体里的 model 是上游返回的真实 ID，网关**不做反向替换**（已确认不需要）


-- ============================================================================
-- 回滚
-- ============================================================================
-- ALTER TABLE mg_model DROP COLUMN upstream_model_id;
