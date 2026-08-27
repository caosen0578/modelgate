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
            vo.sse          = Boolean.TRUE.equals(svc.getSse());
        }
        // 生效 = 授权本身生效 且 服务启用。服务被删（svc==null）一律不生效。
        vo.effective = e.isEffective() && svc != null && !Boolean.FALSE.equals(svc.getEnabled());
        return vo;
    }
}
