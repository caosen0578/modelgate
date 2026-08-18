package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.RequestPayloadEntity;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 报文详情（含三个正文列的全文）。
 *
 * <p>只在<b>单条</b>查询时返回。列表接口一律走 {@link PayloadListVO}，
 * 详情与列表分成两个 VO 就是为了在类型层面堵死"列表顺手把 LONGTEXT 带出来"这条路。
 */
@Data
public class PayloadDetailVO {

    private Long          id;
    private String        traceId;
    private String        appId;
    private String        modelId;
    private Boolean       isStream;
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

    public static PayloadDetailVO of(RequestPayloadEntity e) {
        PayloadDetailVO vo = new PayloadDetailVO();
        vo.id             = e.getId();
        vo.traceId        = e.getTraceId();
        vo.appId          = e.getAppId();
        vo.modelId        = e.getModelId();
        vo.isStream       = e.getIsStream();
        vo.requestBody    = e.getRequestBody();
        vo.responseRaw    = e.getResponseRaw();
        vo.responseText   = e.getResponseText();
        vo.finishReason   = e.getFinishReason();
        vo.upstreamStatus = e.getUpstreamStatus();
        vo.truncated      = e.getTruncated();
        vo.createTime     = e.getCreateTime();
        return vo;
    }
}
