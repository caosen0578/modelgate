package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfo;
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
    private LocalDateTime createdAt;

    public static AppVO of(AppInfo e) {
        AppVO vo = new AppVO();
        vo.appId     = e.getAppId();
        vo.appName   = e.getAppName();
        vo.sysCode   = e.getSysCode();
        vo.appCode   = e.getAppCode();
        vo.owner     = e.getOwner();
        vo.remark    = e.getRemark();
        vo.enabled   = e.getEnabled();
        vo.createdAt = e.getCreatedAt();
        return vo;
    }
}
