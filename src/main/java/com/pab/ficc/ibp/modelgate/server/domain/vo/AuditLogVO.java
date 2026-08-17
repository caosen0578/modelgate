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
    /**
     * 请求路径。DB 与 Entity 一直有 request_path，唯独 VO 漏了这一字段，
     * 审计接口因此从不返回它——排查时看不出调用的是哪个端点。
     * 全量审计（含被拒绝请求）上线后更需要它：不同路径的失败尝试要能区分。
     */
    private String        requestPath;
    private Integer       httpStatus;
    private Integer       latencyMs;
    private Integer       inputTokens;
    private Integer       outputTokens;
    /** Token 口径：usage=上游精确返回 / estimated=本地估算 / none=无。统计报表需据此区分 */
    private String        tokenSource;
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
        vo.requestPath  = e.getRequestPath();
        vo.httpStatus   = e.getHttpStatus();
        vo.latencyMs    = e.getLatencyMs();
        vo.inputTokens  = e.getInputTokens();
        vo.outputTokens = e.getOutputTokens();
        vo.tokenSource  = e.getTokenSource();
        vo.errorMsg     = e.getErrorMsg();
        vo.createTime   = e.getCreateTime();
        return vo;
    }
}
