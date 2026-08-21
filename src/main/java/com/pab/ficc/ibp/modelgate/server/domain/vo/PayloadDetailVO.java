package com.pab.ficc.ibp.modelgate.server.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报文详情（含请求头快照及三个正文列的全文）。
 *
 * <p>只在<b>单条</b>查询时返回。列表接口一律走 {@link PayloadListVO}，
 * 详情与列表分成两个 VO 就是为了在类型层面堵死"列表顺手把 LONGTEXT 带出来"这条路。
 *
 * <p>由 {@code RequestPayloadMapper.selectDetailById} 直接映射（不经 Entity 转换）：
 * Token 三列来自 {@code mg_audit_log}，Entity 里没有它们，走 Entity 反而会丢字段。
 */
@Data
public class PayloadDetailVO {

    private Long          id;
    private String        traceId;
    private String        appId;
    private String        modelId;
    private Boolean       isStream;
    /** 全部入站请求头 JSON，可能包含可直接使用的凭证信息 */
    private String        requestHeaders;
    /** 请求报文原文（JSON）。⚠️ 含用户原始 prompt，脱敏方案待定 */
    private String        requestBody;
    /** 响应原文：流式为完整 SSE 流（含 data: 前缀与全部分帧），非流式为原始 JSON */
    private String        responseRaw;
    /** 响应正文：流式为各帧 delta 拼接结果，非流式为 message.content */
    private String        responseText;
    private String        finishReason;
    private Integer       upstreamStatus;
    private String        truncated;
    private LocalDateTime createTime;

    /** 输入 Token，取自 mg_audit_log（报文表按设计不冗余存）。为空表示对应审计记录不存在 */
    private Integer       inputTokens;
    /** 输出 Token，同上取自 mg_audit_log */
    private Integer       outputTokens;
    /** Token 口径：usage=上游精确返回 / estimated=本地估算 / none=无 */
    private String        tokenSource;

    /**
     * 同 traceId 在 mg_audit_log 里命中的行数；>1 表示 Token 存疑。
     * 详见 {@link PayloadListVO#getAuditMatchCount()}。
     */
    private Integer       auditMatchCount;
}
