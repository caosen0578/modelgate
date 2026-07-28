package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLogEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditLogVO {

    private Long          id;
    private String        traceId;
    private String        appId;
    private String        modelId;
    private String        credType;
    private String        clientIp;
    private Integer       httpStatus;
    private Integer       latencyMs;
    private Integer       inputTokens;
    private Integer       outputTokens;
    private String        errorMsg;
    private LocalDateTime createTime;

    public static AuditLogVO of(AuditLogEntity e) {
        AuditLogVO vo = new AuditLogVO();
        vo.id           = e.getId();
        vo.traceId      = e.getTraceId();
        vo.appId        = e.getAppId();
        vo.modelId      = e.getModelId();
        vo.credType     = e.getCredType();
        vo.clientIp     = e.getClientIp();
        vo.httpStatus   = e.getHttpStatus();
        vo.latencyMs    = e.getLatencyMs();
        vo.inputTokens  = e.getInputTokens();
        vo.outputTokens = e.getOutputTokens();
        vo.errorMsg     = e.getErrorMsg();
        vo.createTime   = e.getCreateTime();
        return vo;
    }
}
