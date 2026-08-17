package com.pab.ficc.ibp.modelgate.server.domain.vo;

import lombok.Data;

/**
 * 连通性测试结果。
 *
 * <p>{@code success} 仅在上游返回 2xx 时为 true——即"这条配置真的能把请求送到模型并拿到正常响应"。
 * 任何鉴权失败、地址错误、网络不可达都必须为 false，不得因为"请求发出去了"就判定成功。
 */
@Data
public class ConnectivityTestVO {

    /** 是否连通（上游返回 2xx 才为 true） */
    private Boolean success;

    /** 端到端耗时（毫秒），含连接建立与上游处理；失败时为实际失败耗时 */
    private Long latencyMs;

    /** 人类可读的结论，失败时说明具体原因与排查方向 */
    private String message;

    /** 上游 HTTP 状态码；未建立连接（DNS/超时/拒绝）时为 null */
    private Integer httpStatus;

    /** 实际请求的上游地址，便于确认 URL 拼接是否符合预期 */
    private String upstreamUri;

    /** 上游响应体片段（截断），失败排查用 */
    private String upstreamBody;

    public static ConnectivityTestVO of(boolean success, long latencyMs, String message,
                                        Integer httpStatus, String upstreamUri, String upstreamBody) {
        ConnectivityTestVO vo = new ConnectivityTestVO();
        vo.success = success;
        vo.latencyMs = latencyMs;
        vo.message = message;
        vo.httpStatus = httpStatus;
        vo.upstreamUri = upstreamUri;
        vo.upstreamBody = upstreamBody;
        return vo;
    }
}
