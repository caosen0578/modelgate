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

    /** 请求报文字符数（CHAR_LENGTH，非字节数） */
    private Long          requestLen;
    /** 响应原文字符数 */
    private Long          responseRawLen;
    /** 响应正文字符数 */
    private Long          responseTextLen;
    /** 响应正文前 160 字，用于在列表上快速辨认 */
    private String        responsePreview;
}
