package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppServiceAuthEntity;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 反查：某个服务被哪些接入系统授权。
 *
 * <p>与模型侧的 {@code ModelAuthorizedAppVO} 同形态。{@link #effective} 的口径也一致：
 * 「接入系统已启用 且 授权已启用 且 未过期」，三者任一不满足该系统就实际调不通本服务——
 * 这是排查"某系统调不通"时第一个要看的结论，所以直接算好给出，不让人自己拼。
 */
@Data
public class ServiceAuthorizedAppVO {

    private String        appId;
    private String        serviceId;
    /** 接入系统名称；系统已删除但授权行残留时为 null */
    private String        appName;
    private String        systemName;
    private String        appCode;
    private String        owner;
    private Boolean       appEnabled;
    private Boolean       authEnabled;
    private Integer       qpsLimit;
    private Integer       dailyLimit;
    private LocalDateTime expiresAt;
    /** 接入系统已启用 且 授权已启用 且 未过期 */
    private Boolean       effective;
    private LocalDateTime createTime;

    public static ServiceAuthorizedAppVO of(AppServiceAuthEntity e, AppInfoEntity app) {
        ServiceAuthorizedAppVO vo = new ServiceAuthorizedAppVO();
        vo.appId       = e.getAppId();
        vo.serviceId   = e.getServiceId();
        vo.authEnabled = e.getEnabled();
        vo.qpsLimit    = e.getQpsLimit();
        vo.dailyLimit  = e.getDailyLimit();
        vo.expiresAt   = e.getExpiresAt();
        vo.createTime  = e.getCreateTime();
        if (app != null) {
            vo.appName    = app.getAppName();
            vo.systemName = app.getSystemName();
            vo.appCode    = app.getAppCode();
            vo.owner      = app.getOwner();
            vo.appEnabled = app.getEnabled();
        }
        vo.effective = e.isEffective() && app != null && !Boolean.FALSE.equals(app.getEnabled());
        return vo;
    }
}
