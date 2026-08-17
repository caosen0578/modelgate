-- ============================================================================
-- upgrade_20260813_request_payload_log.sql
--
-- 目的：新增「请求/响应报文记录」能力，并把 Token 统计从"恒为 0"修成真实值。
--
-- 背景（必读）：
--   改造前 TraceFilter 写审计时 input_tokens / output_tokens 是硬编码的 0，导致
--     · mg_audit_log 的 token 两列全表为 0
--     · 用量统计页 SUM(input_tokens) 在累加一堆 0，数据全是假的
--     · AuditService.accumulateMonthlyTokens 累加的也是 0 → Redis 月度计数器恒 0
--     · QuotaFilter 的月度 Token 配额从未生效过
--   本次通过解析上游响应的 usage 字段补齐 token，上述四项一并修复。
--
-- 三项变更：
--   1. mg_app_model_auth  加 payload_log_enabled —— 报文记录开关，按「接入系统 × 模型」控制
--   2. mg_audit_log       加 token_source        —— 标记 token 是精确值还是估算值
--   3. 新建 mg_request_payload                   —— 报文留档表
--
-- 执行：mysql -h.. -P.. -u.. -p 库名 < sql/upgrade_20260813_request_payload_log.sql
-- ============================================================================

-- ----------------------------------------------------------------------------
-- 1. 授权表：报文记录开关
--    粒度定在授权行（app × model）而非全局：不同接入系统的合规要求不同，
--    且报文量差异极大，需要能只对关注的系统开启。
-- ----------------------------------------------------------------------------
ALTER TABLE mg_app_model_auth
    ADD COLUMN payload_log_enabled TINYINT(1) NOT NULL DEFAULT 0
    COMMENT '请求/响应报文记录开关：1=记录到 mg_request_payload，0=不记录（默认关闭）'
    AFTER monthly_tokens;

-- ----------------------------------------------------------------------------
-- 2. 审计表：Token 口径标记
--    统计报表必须能区分"精确"与"估算"两种口径，否则算出来的账没人敢认。
--      usage     = 从上游响应的 usage 字段解析，精确
--      estimated = 上游未返回 usage（如 OpenAI 流式默认不带），按字符估算
--      none      = 请求失败或无响应体，无 token
-- ----------------------------------------------------------------------------
ALTER TABLE mg_audit_log
    ADD COLUMN token_source VARCHAR(16) DEFAULT NULL
    COMMENT 'Token 来源口径：usage=上游精确返回 estimated=本地估算 none=无'
    AFTER output_tokens;

-- ----------------------------------------------------------------------------
-- 3. 报文表
--
--    为什么独立成表而不是给 mg_audit_log 加列：
--      · mg_audit_log 是全量高频追加表，列表页一次 SELECT 会把几十 KB 大字段全拖出来
--      · 报文只在开关开启时才产生，两者数据量差一个数量级
--      · 报文的保留期应远短于审计，需要独立清理策略
--    两表通过 trace_id 关联。
--
--    为什么 app_id / model_id 冗余：避免每次按接入系统或模型筛报文都要 join 审计表。
--
--    为什么三个正文列都用 LONGTEXT：按决策不设大小上限（4GB 上限，实际由应用层
--    内存保护阈值兜底，见 modelgate.payload-log.max-capture-bytes）。
--
--    ⚠️ Token 不在本表冗余存储：唯一真相在 mg_audit_log，统计一律走审计表
--       （审计是全量的，报文表只有开了开关的那部分），避免两处口径不一致。
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mg_request_payload (
    id              BIGINT       NOT NULL AUTO_INCREMENT        COMMENT '主键，自增',
    trace_id        VARCHAR(64)  NOT NULL                       COMMENT '全链路追踪 ID，关联 mg_audit_log.trace_id',
    app_id          VARCHAR(64)                                 COMMENT '调用方 appId（冗余自审计，便于按接入系统直接筛选）',
    model_id        VARCHAR(64)                                 COMMENT '模型 ID（冗余自审计，便于按模型直接筛选）',
    is_stream       TINYINT(1)   NOT NULL DEFAULT 0             COMMENT '是否流式响应：1=SSE 流式，0=一次性返回',
    request_body    LONGTEXT                                    COMMENT '请求报文原文（JSON）。⚠️ 含用户原始 prompt，脱敏方案待定',
    response_raw    LONGTEXT                                    COMMENT '响应报文原文：流式为完整 SSE 流（含 data: 前缀与所有分帧），非流式为原始 JSON。供排查时完整复现上游返回',
    response_text   LONGTEXT                                    COMMENT '响应正文：流式为各帧 delta.content 拼接后的最终文本，非流式为 choices[0].message.content。供查询展示，人可直读',
    finish_reason   VARCHAR(32)                                 COMMENT '结束原因：stop|length|tool_calls|content_filter 等，取自响应最后一帧',
    upstream_status INT                                         COMMENT '上游返回的 HTTP 状态码',
    truncated       VARCHAR(16)  NOT NULL DEFAULT 'none'        COMMENT '截断标记：none=完整 request=请求被截 response=响应被截 both=均被截（仅在触发内存保护阈值时出现）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录写入时间（用于保留期清理）',
    PRIMARY KEY (id),
    KEY idx_trace_id        (trace_id),
    KEY idx_app_model_time  (app_id, model_id, create_time),
    KEY idx_create_time     (create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='模型调用请求/响应报文留档（按授权开关记录，保留期独立于审计）';

-- ----------------------------------------------------------------------------
-- 校验
-- ----------------------------------------------------------------------------
SELECT COUNT(*) AS payload_table_ready FROM information_schema.TABLES
 WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'mg_request_payload';

SELECT COLUMN_NAME, COLUMN_TYPE, COLUMN_COMMENT FROM information_schema.COLUMNS
 WHERE TABLE_SCHEMA = DATABASE()
   AND ((TABLE_NAME = 'mg_app_model_auth' AND COLUMN_NAME = 'payload_log_enabled')
     OR (TABLE_NAME = 'mg_audit_log'      AND COLUMN_NAME = 'token_source'));

-- ============================================================================
-- 回滚脚本
-- ⚠️ DROP TABLE 会丢失全部已记录报文，执行前确认无需留存
-- ============================================================================
-- DROP TABLE IF EXISTS mg_request_payload;
-- ALTER TABLE mg_app_model_auth DROP COLUMN payload_log_enabled;
-- ALTER TABLE mg_audit_log      DROP COLUMN token_source;
