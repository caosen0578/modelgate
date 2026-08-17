package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppVO {

    private String        appId;
    private String        appName;
    private String        sysCode;
    private String        appCode;
    private String        owner;
    private String        remark;
    private Boolean       enabled;
    private LocalDateTime createTime;

    /** 已授权模型数（授权记录数，不区分是否生效）；列表页展示用 */
    private Long          authCount;
    /** 凭证数（含已禁用/已过期）；列表页展示用 */
    private Long          credCount;

    public static AppVO of(AppInfoEntity e) {
        AppVO vo = new AppVO();
        vo.appId     = e.getAppId();
        vo.appName   = e.getAppName();
        vo.sysCode   = e.getSysCode();
        vo.appCode   = e.getAppCode();
        vo.owner     = e.getOwner();
        vo.remark    = e.getRemark();
        vo.enabled   = e.getEnabled();
        vo.createTime = e.getCreateTime();
        return vo;
    }

    /**
     * 列表页用：额外带上授权模型数与凭证数。
     *
     * <p>缺省归零而非留空——前端此前读不到这两个字段时靠 {@code ||0} 兜底，
     * 结果是"有数据也恒显示 0 个"，问题被兜底逻辑掩盖了。
     */
    public static AppVO of(AppInfoEntity e, Long authCount, Long credCount) {
        AppVO vo = of(e);
        vo.authCount = authCount != null ? authCount : 0L;
        vo.credCount = credCount != null ? credCount : 0L;
        return vo;
    }
}
