package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceCredentialEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 服务列表 / 详情返回体。
 *
 * <p><b>绝不携带 SK 明文</b>：列表接口一次返回几十条，任何一条泄露都等于把上游凭证发给了浏览器。
 * 需要看明文时走单条的 {@code /services/secret}，按需解密。
 */
@Data
public class ServiceVO {

    private String  serviceId;
    private String  serviceName;
    private String  ownerSystem;
    private String  upstreamUrl;
    private Boolean stripPrefix;
    private Integer timeoutMs;
    private Boolean sse;
    private Boolean payloadLogEnabled;

    /** 连通性探测配置：方法 / 路径 / 请求体。测试工具据此预填，也允许临时覆盖。 */
    private String  probeMethod;
    private String  probePath;
    private String  probeBody;
    private String  description;
    private Boolean enabled;

    /** 对外完整前缀，前端直接展示、可一键复制，省得运营自己拼 */
    private String  publicPrefix;

    /** 上游鉴权类型（none|api_key|ak_sk|custom_header），仅类型不含密钥 */
    private String  authType;
    /** AK 明文——它本身不是密钥，展示出来便于核对配置是否填错 */
    private String  accessKey;
    /** 是否配了 SK，供页面显示「已配置 / 未配置」而不必回显明文 */
    private Boolean secretConfigured;
    /** 是否配了附加请求头 */
    private Boolean extraHeadersConfigured;

    /** 授权系统数量，由列表接口一次性聚合回填（避免逐行 N+1） */
    private Long    authorizedAppCount;

    private LocalDateTime createTime;

    /**
     * 更新时间。
     *
     * <p>模型侧的 {@code AppModelAuthVO} 曾漏掉这个字段，症状是页面上「更新时间」永远为空、
     * 看起来像数据库没存值，实际值一直都在。此处一并带上，避免重蹈覆辙。
     */
    private LocalDateTime updateTime;

    public static ServiceVO of(ServiceEntity e) {
        ServiceVO vo = new ServiceVO();
        vo.serviceId         = e.getServiceId();
        vo.serviceName       = e.getServiceName();
        vo.ownerSystem       = e.getOwnerSystem();
        vo.upstreamUrl       = e.getUpstreamUrl();
        vo.stripPrefix       = e.shouldStripPrefix();
        vo.timeoutMs         = e.getTimeoutMs();
        vo.sse               = Boolean.TRUE.equals(e.getSse());
        vo.payloadLogEnabled = Boolean.TRUE.equals(e.getPayloadLogEnabled());
        vo.probeMethod       = e.resolveProbeMethod();
        vo.probePath         = e.getProbePath();
        vo.probeBody         = e.getProbeBody();
        vo.description       = e.getDescription();
        vo.enabled           = e.getEnabled();
        vo.publicPrefix      = "/svc/" + e.getServiceId() + "/";
        vo.createTime        = e.getCreateTime();
        vo.updateTime        = e.getUpdateTime();
        return vo;
    }

    /** 带凭证信息（只带类型与 AK，密钥一律只给"是否已配置"）。 */
    public static ServiceVO of(ServiceEntity e, ServiceCredentialEntity cred) {
        ServiceVO vo = of(e);
        if (cred != null) {
            vo.authType               = cred.getAuthType();
            vo.accessKey              = cred.getAccessKey();
            vo.secretConfigured       = cred.getSecretKey() != null && !cred.getSecretKey().isBlank();
            vo.extraHeadersConfigured = cred.getExtraHeaders() != null && !cred.getExtraHeaders().isBlank();
        } else {
            vo.authType               = "none";
            vo.secretConfigured       = Boolean.FALSE;
            vo.extraHeadersConfigured = Boolean.FALSE;
        }
        return vo;
    }
}
