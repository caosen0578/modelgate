package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 「某个模型被哪些接入系统授权」的反查视图。
 *
 * <p>与 {@link AppModelAuthVO}（按 appId 正查）相对，本视图按 modelId 反查，
 * 并联出接入系统的名称与负责人——只有 appId 的话，运维看不出来是谁在用。
 */
@Data
public class ModelAuthorizedAppVO {

    private String        appId;
    /** 所属业务系统中文名；与 appName 一起返回，避免模型授权反查页丢失新增维度。 */
    private String        systemName;
    private String        appName;
    private String        sysCode;
    /** 负责人：模型要下线/调整时找谁 */
    private String        owner;

    /** 接入系统自身是否启用 */
    private Boolean       appEnabled;
    /** 本条授权是否启用 */
    private Boolean       authEnabled;
    private LocalDateTime expiresAt;

    /**
     * 授权是否**实际生效**。
     *
     * <p>按 [01 §5 开关优先级]：系统开关关闭 → 该系统所有请求被拒，授权开着也没用；
     * 授权过期等同于关闭。只看 {@code authEnabled} 会误判，因此单独算一个结论位，
     * 避免运维以为"授权是开的所以能用"。
     */
    private Boolean       effective;
    /** 不生效的原因，effective=true 时为 null */
    private String        ineffectiveReason;

    private Integer       qpsLimit;
    private Integer       dailyLimit;
    private Long          monthlyTokens;
    private LocalDateTime createTime;

    public static ModelAuthorizedAppVO of(AppModelAuthEntity auth, AppInfoEntity app) {
        ModelAuthorizedAppVO vo = new ModelAuthorizedAppVO();
        vo.appId         = auth.getAppId();
        vo.authEnabled   = auth.getEnabled();
        vo.expiresAt     = auth.getExpiresAt();
        vo.qpsLimit      = auth.getQpsLimit();
        vo.dailyLimit    = auth.getDailyLimit();
        vo.monthlyTokens = auth.getMonthlyTokens();
        vo.createTime    = auth.getCreateTime();

        if (app != null) {
            vo.systemName = app.getSystemName();
            vo.appName    = app.getAppName();
            vo.sysCode    = app.getSysCode();
            vo.owner      = app.getOwner();
            vo.appEnabled = app.getEnabled();
        }

        boolean expired = auth.getExpiresAt() != null && auth.getExpiresAt().isBefore(LocalDateTime.now());
        if (app == null) {
            // 接入系统已被删除但授权行还在——数据不一致，必须显式暴露而不是静默当作可用
            vo.effective = false;
            vo.ineffectiveReason = "接入系统不存在（可能已删除），授权残留";
        } else if (!Boolean.TRUE.equals(app.getEnabled())) {
            vo.effective = false;
            vo.ineffectiveReason = "接入系统已禁用";
        } else if (!Boolean.TRUE.equals(auth.getEnabled())) {
            vo.effective = false;
            vo.ineffectiveReason = "授权已禁用";
        } else if (expired) {
            vo.effective = false;
            vo.ineffectiveReason = "授权已过期";
        } else {
            vo.effective = true;
        }
        return vo;
    }
}
