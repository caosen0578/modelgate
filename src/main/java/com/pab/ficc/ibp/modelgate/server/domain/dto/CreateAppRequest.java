package com.pab.ficc.ibp.modelgate.server.domain.dto;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfo;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class CreateAppRequest {

    @NotBlank private String appId;
    @NotBlank private String appName;
    @NotBlank private String sysCode;
    @NotBlank private String appCode;
    @NotBlank private String owner;
    private String remark;

    public AppInfo toEntity() {
        var a = new AppInfo();
        a.setAppId(appId);
        a.setAppName(appName);
        a.setSysCode(sysCode);
        a.setAppCode(appCode);
        a.setOwner(owner);
        a.setRemark(remark);
        a.setEnabled(true);
        return a;
    }
}
