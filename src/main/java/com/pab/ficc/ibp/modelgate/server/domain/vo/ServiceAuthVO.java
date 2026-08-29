package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppServiceAuthEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.ServiceEntity;
import lombok.Data;

import java.time.LocalDateTime;

/** 某接入系统的服务授权列表项，联查回填服务名称与对外前缀。 */
@Data
public class ServiceAuthVO {

    private String        appId;
    private String        serviceId;
    /** 服务展示名；服务已删除但授权行残留时为 null，前端回落显示 serviceId */
    private String        serviceName;
    /** 对外前缀，前端「接入示例」直接用它拼 base URL */
    private String        publicPrefix;
    /**
     * 上游地址与是否剥前缀，仅供「接入示例」把"网关 → 上游"那一行显示成**真实地址**。
     *
     * <p>不带这两个字段时该行只能打印占位符 {@code {该服务的上游地址}}，
     * 而且**算不出尾斜杠**——补不补取决于 upstreamUrl 自己带不带路径
     * （见 {@code ServiceEntity.joinUpstream}），于是预览与真实转发不一致。
     * 服务管理页本来就明文展示 upstreamUrl，这里不构成新的暴露面。
     */
    private String        upstreamUrl;
    private Boolean       stripPrefix;
    private Boolean       sse;
    private Boolean       enabled;
    private Integer       qpsLimit;
    private Integer       dailyLimit;
    private LocalDateTime expiresAt;
    /** 是否实际生效：授权启用 且 未过期 且 服务本身启用 */
    private Boolean       effective;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public static ServiceAuthVO of(AppServiceAuthEntity e, ServiceEntity svc) {
        ServiceAuthVO vo = new ServiceAuthVO();
        vo.appId      = e.getAppId();
        vo.serviceId  = e.getServiceId();
        vo.enabled    = e.getEnabled();
        vo.qpsLimit   = e.getQpsLimit();
        vo.dailyLimit = e.getDailyLimit();
        vo.expiresAt  = e.getExpiresAt();
        vo.createTime = e.getCreateTime();
        vo.updateTime = e.getUpdateTime();
        if (svc != null) {
            vo.serviceName  = svc.getServiceName();
            vo.publicPrefix = "/svc/" + svc.getServiceId() + "/";
            vo.upstreamUrl  = svc.normalizedUpstreamUrl();
            vo.stripPrefix  = svc.shouldStripPrefix();
            vo.sse          = Boolean.TRUE.equals(svc.getSse());
        }
        // 生效 = 授权本身生效 且 服务启用。服务被删（svc==null）一律不生效。
        vo.effective = e.isEffective() && svc != null && !Boolean.FALSE.equals(svc.getEnabled());
        return vo;
    }
}
