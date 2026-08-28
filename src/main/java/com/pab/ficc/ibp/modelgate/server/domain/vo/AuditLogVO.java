package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AuditLogEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AuditLogVO {

    private Long          id;
    private String        traceId;
    private String        appId;
    /**
     * 应用编码（{@code mg_app.app_code}）。
     *
     * <p><b>不来自审计表</b>——审计表只存 app_id。由 Controller 用 {@code AppScopeResolver}
     * 按当前页的 appId 一次性批量反查后回填，应用已删除时为 null，前端显示为 "-"。
     */
    private String        appCode;
    /** 流量类型：model=模型流量 service=通用服务流量。前端据此决定「模型/服务」列显示哪个值 */
    private String        trafficType;
    private String        modelId;
    /** 服务编码（traffic_type=service 时有值） */
    private String        serviceId;
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
        // trafficType 兜底成 "model"：历史数据在加列之前写入，读出来虽然有 DB 默认值，
        // 但走内存路径（如单测直接 new Entity）时可能为 null，前端拿到 null 会渲染成空白列
        vo.trafficType  = e.getTrafficType() == null ? "model" : e.getTrafficType();
        vo.modelId      = e.getModelId();
        vo.serviceId    = e.getServiceId();
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
