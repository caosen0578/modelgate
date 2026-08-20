-- ============================================================================
-- upgrade_20260819_force_params_review.sql
--
-- ⚠️⚠️ 这不是一个"执行就完事"的脚本，是一份**必须在重启前处理掉的待办**。
--
-- 【背景】
--   mg_model 的 default_params / force_params / param_mapping 三个字段，
--   管理面一直让运维填、保存也成功，但**运行期从来没有任何代码读取过**（死配置）。
--   本次已把它们接线（RequestParamMerger），也就是说——
--
--       **库里已经填着的值，会在应用重启后立刻开始生效。**
--
--   这是一次行为变更，不是新功能上线。重启前必须确认现有值确实是你想要的。
--
-- 【当前库里的实际内容（2026-08-19 巡检结果）】
--   deepseek-ai/DeepSeek-R1   force_params = {"max_tokens": 512}
--   deepseek-ai/DeepSeek-V3   force_params = {"max_tokens": 512,"stream": true}
--
--   这两条如果原样生效，后果是：
--     · max_tokens=512 会**强制覆盖**调用方传的值 —— 所有回答被截断在 512 token，
--       对话系统上表现为"模型说到一半就没了"；
--     · "stream": true 会**强制把所有请求变成流式** —— 非流式调用方本来期望一个
--       JSON 响应，却收到 SSE 事件流，客户端多半直接解析失败。
--
--   从取值看（512 这种小值、强制 stream）更像是早期联调留下的测试数据，
--   而不是有意的生产管控策略。**请确认后再重启。**
-- ============================================================================


-- ----------------------------------------------------------------------------
-- 第一步：看看现在都配了什么（只读）
-- ----------------------------------------------------------------------------
SELECT  model_id                AS 模型ID,
        api_spec                AS 接口规范,
        default_params          AS 默认参数_业务可覆盖,
        force_params            AS 强制参数_业务不可覆盖,
        param_mapping           AS 字段映射_仅custom生效
  FROM  mg_model
 WHERE  is_del = '0'
   AND (COALESCE(default_params, '') <> ''
     OR COALESCE(force_params,   '') <> ''
     OR COALESCE(param_mapping,  '') <> '')
 ORDER  BY model_id;


-- ----------------------------------------------------------------------------
-- 第二步：按结论三选一
-- ----------------------------------------------------------------------------

-- 【选项 A】确认这些值是**测试残留**，清空（推荐，除非你确实要这些管控）
-- UPDATE mg_model
--    SET force_params = NULL
--  WHERE is_del = '0'
--    AND model_id IN ('deepseek-ai/DeepSeek-R1', 'deepseek-ai/DeepSeek-V3');

-- 【选项 B】只去掉最危险的 "stream": true，保留 max_tokens 管控
--          （注意 512 是否是你要的上限，多数对话场景应放大到 4096 以上）
-- UPDATE mg_model
--    SET force_params = '{"max_tokens": 4096}'
--  WHERE is_del = '0'
--    AND model_id = 'deepseek-ai/DeepSeek-V3';
-- UPDATE mg_model
--    SET force_params = '{"max_tokens": 4096}'
--  WHERE is_del = '0'
--    AND model_id = 'deepseek-ai/DeepSeek-R1';

-- 【选项 C】确认原值就是要的，什么都不做（重启后即生效）


-- ============================================================================
-- 附：模型 ID 改名怎么配（2026-08-20 更正）
--
-- 模型身份现已由专用 upstream_model_id 字段收口，禁止再藏进 force_params.model：
--   对外暴露 model_id = 'deepseek-v3-platform-b'（平台上不重名，可以新增地址）
--   转发时改回真实 ID：
--
--     UPDATE mg_model
--        SET upstream_model_id = 'deepseek-ai/DeepSeek-V3'
--      WHERE model_id = 'deepseek-v3-platform-b' AND is_del = '0';
--
-- 投产前先查有没有旧写法；有结果必须逐条把 $.model 迁到 upstream_model_id，
-- 并从 force_params 删除 model 键（其余强制参数保留）：
--
--   SELECT model_id, upstream_model_id, force_params
--     FROM mg_model
--    WHERE is_del = '0'
--      AND JSON_CONTAINS_PATH(
--            CASE WHEN JSON_VALID(force_params) THEN force_params ELSE '{}' END,
--            'one', '$.model');
--   CASE 不是多余的：SQL 优化器不承诺按 WHERE 书写顺序短路；直接写
--   JSON_VALID(x) AND JSON_CONTAINS_PATH(x,...) 遇到历史非法 JSON 仍可能抛错。
--
-- 要点：
--   · 对外 ID 与上游 ID 完全解耦，调用方无感知
--   · 同一个真实模型可以挂任意多个对外 ID —— 多建几条 mg_model 记录即可，
--     每条有独立的 upstream_url（正好对应你两个平台两个域名的场景）
--   · 审计与配额仍按**对外 ID** 统计，账不会乱
--   · 响应体里的 model 字段是上游返回的**真实 ID**，网关不做反向替换
--     （已与你确认：不需要换回来）
--
-- 性能：仅配置 upstream_model_id 时走字节级定点替换，不建 JSON 树；
--       同时配置 default/force/mapping 时复用那次必要的解析，不增加第二次解析。
-- ============================================================================
