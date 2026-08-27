package com.pab.ficc.ibp.modelgate.server.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 模型调用请求/响应报文留档。
 *
 * <p>仅当对应授权行 {@code mg_app_model_auth.payload_log_enabled=1} 时才产生记录，
 * 通过 {@code trace_id} 与 {@link AuditLogEntity} 关联。
 *
 * <p><b>不含基础字段</b>（creater/updater/is_del 等）：与 {@code mg_audit_log} 同属高频追加表，
 * 由 {@code PayloadCleanupService} 按 {@code create_time} 物理清理，无留痕需求。
 *
 * <p><b>不冗余存 Token</b>：Token 的唯一真相在 {@code mg_audit_log}，
 * 统计一律走审计表（审计是全量的，本表只有开了开关的那部分），避免两处口径不一致。
 */
@Data
@TableName("mg_request_payload")
public class RequestPayloadEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 全链路追踪 ID，关联 mg_audit_log.trace_id */
    private String traceId;

    /**
     * 流量类型：{@code model} = 模型流量，{@code service} = 通用服务流量。
     *
     * <p><b>字段默认值 "model" 是刻意的兜底，不得删除</b>：DB 列是
     * {@code NOT NULL DEFAULT 'model'}，而 MyBatis-Plus 可能把 null 值以显式 NULL
     * 写进 INSERT、覆盖掉建表默认值，那会直接插入失败。与 {@code isDel = "0"} 同一类保险，
     * 理由见该字段注释（那个坑真实发生过，见 spec/modelgate/11 §17）。
     *
     * <p>现有用量统计 SQL 按 {@code model_id} 分组，服务流量必须能被排除，
     * 所以查询侧要补 {@code WHERE traffic_type = 'model'}；默认值保证历史数据自动归位。
     */
    private String trafficType = "model";

    /** 冗余自审计，便于按接入系统直接筛选而不必 join */
    private String appId;

    /** 冗余自审计，便于按模型直接筛选而不必 join */
    private String modelId;

    /** 服务编码（{@code traffic_type=service} 时写入；模型流量为 NULL） */
    private String serviceId;

    /** 是否 SSE 流式响应，决定 response_raw 的结构 */
    private Boolean isStream;

    /**
     * 全部入站请求头 JSON 快照，包含 Authorization/Cookie/API Key 等凭证字段；
     * 仅受字符保险丝限制，生产环境必须限制管理面与数据库访问权限。
     */
    private String requestHeaders;

    /** 请求报文原文（JSON）。⚠️ 含用户原始 prompt，脱敏方案待定 */
    private String requestBody;

    /** 响应原文：流式为完整 SSE 流（含 data: 前缀与全部分帧），非流式为原始 JSON */
    private String responseRaw;

    /** 响应正文：流式为各帧 delta 拼接结果，非流式为 message.content */
    private String responseText;

    /** 结束原因：stop|length|tool_calls|content_filter 等 */
    private String finishReason;

    /** 上游返回的 HTTP 状态码 */
    private Integer upstreamStatus;

    /** 截断标记：none|request|response|both，仅在触发内存保护阈值时非 none */
    private String truncated;

    private LocalDateTime createTime;
}
