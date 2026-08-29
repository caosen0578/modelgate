package com.pab.ficc.ibp.modelgate.server.domain.bo;

import lombok.Data;

/**
 * 报文回放入参。
 *
 * <p>三个字段都<b>可空</b>，空则回落到留档里的原值——这样"原样重发"不用传任何东西，
 * "改点参数再试"只传要改的那一个。
 */
@Data
public class ReplayPayloadRequestBO {

    /**
     * 要发送的请求体；留空则用留档里的 {@code request_body} 原文。
     *
     * <p>允许修改是回放的主要价值：排查时往往要"把 max_tokens 调小再试"
     * 或"去掉某个字段看还复不复现"。
     */
    private String requestBody;

    /**
     * HTTP 方法，仅服务流量有意义（模型流量恒为 POST）。
     * 留空则用留档的 {@code request_method}，再空则 POST。
     */
    private String method;

    /**
     * 上游路径，仅服务流量有意义。留空则从留档的 {@code request_path} 里剥掉
     * {@code /svc/{服务编码}} 得到。
     *
     * <p>{@code upgrade_20260829} 之前的历史报文没有 {@code request_path}，
     * 这两处都取不到值时必须由使用者手工填写——管理页会明确提示。
     */
    private String path;
}
