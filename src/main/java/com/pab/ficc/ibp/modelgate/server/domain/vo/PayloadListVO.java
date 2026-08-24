package com.pab.ficc.ibp.modelgate.server.domain.vo;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报文列表行。
 *
 * <p><b>刻意不含三个 LONGTEXT 正文列</b>：一页 20 条报文的正文合计可达几十 MB，
 * 列表接口若把它们一起返回，会同时压垮 DB 网络传输、服务端堆内存和浏览器渲染
 * ——这正是当初把报文独立成表、不塞进 {@code mg_audit_log} 的理由，
 * 列表查询必须把这个理由贯彻到底。
 *
 * <p>取而代之给出<b>长度</b>与<b>正文预览</b>（SQL 侧 {@code CHAR_LENGTH} / {@code LEFT} 截取，
 * 不把整列拖出来），足够在列表上判断"是不是我要找的那条"；全文走
 * {@code GET /modelgate/payload/detail} 单条按需拉取。
 *
 * <p>字段由 {@code RequestPayloadMapper.selectListPage} 的列别名按下划线转驼峰直接映射。
 */
@Data
public class PayloadListVO {

    private Long          id;
    private String        traceId;
    private String        appId;
    /**
     * 应用编码（{@code mg_app.app_code}）。
     *
     * <p><b>不是 {@code selectListPage} 查出来的</b>——报文表只存 app_id，
     * 由 Controller 用 {@code AppScopeResolver} 按当前页 appId 批量反查后回填。
     * 刻意不在列表 SQL 里 JOIN {@code mg_app}，理由见该方法注释。
     */
    private String        appCode;
    private String        modelId;
    /** 是否 SSE 流式，决定 responseRaw 的结构（详情页据此选择展示方式） */
    private Boolean       isStream;
    /** 结束原因：stop|length|tool_calls|content_filter 等 */
    private String        finishReason;
    /** 上游返回的 HTTP 状态码 */
    private Integer       upstreamStatus;
    /** 截断标记：none|request|response|both */
    private String        truncated;
    private LocalDateTime createTime;

    /**
     * 输入 Token 数。
     *
     * <p><b>取自 {@code mg_audit_log}，不是本表的列</b>——报文表按设计不冗余存 Token
     * （唯一真相在审计表）。为空说明对应审计记录已过保留期或不存在：审计保留 90 天、
     * 报文保留 7 天，正常情况下报文在、审计必在，查不到即数据异常，
     * 页面按"-"展示而不是按 0 展示，两者含义完全不同。
     */
    private Integer       inputTokens;
    /** 输出 Token 数，同上取自 mg_audit_log */
    private Integer       outputTokens;
    /** Token 口径：usage=上游精确返回 / estimated=本地估算 / none=无。估算值不能当账算，必须标出来 */
    private String        tokenSource;

    /**
     * 同 traceId 在 mg_audit_log 里命中的行数。
     *
     * <p>正常是 1。大于 1 说明<b>这个 traceId 被多次调用共用</b>——
     * {@code TraceWebFilter.resolveTraceId} 优先沿用调用方传入的 {@code X-Trace-Id}，
     * 一次上游事务扇出成多次模型调用时就会这样。此时上面三个 Token 字段取的是其中
     * id 最小的那一行，<b>未必属于当前这条报文</b>，前端必须把它标成存疑而不是照直显示。
     */
    private Integer       auditMatchCount;

    /** 请求报文字符数（CHAR_LENGTH，非字节数），用于在预览的 tooltip 上提示报文规模 */
    private Long          requestLen;
    /** 全部请求头 JSON 字符数 */
    private Long          requestHeadersLen;
    /** 响应原文字符数 */
    private Long          responseRawLen;
    /** 响应正文字符数 */
    private Long          responseTextLen;
    /** 请求报文前 160 字，用于在列表上直接看出这次调用问了什么 */
    private String        requestPreview;
    /** 全部请求头 JSON 前 160 字，用于快速辨认用户信息 */
    private String        requestHeadersPreview;
    /** 响应正文前 160 字，用于在列表上快速辨认 */
    private String        responsePreview;
}
