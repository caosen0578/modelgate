package com.pab.ficc.ibp.modelgate.server.domain.dto;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfo;
import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class UpdateAppRequest {

    @NotBlank private String appId;
    @NotBlank private String appName;
    @NotBlank private String sysCode;
    @NotBlank private String appCode;
    @NotBlank private String owner;
    private String remark;
    private Boolean enabled;

    public AppInfo toEntity() {
        var a = new AppInfo();
        a.setAppName(appName);
        a.setSysCode(sysCode);
        a.setAppCode(appCode);
        a.setOwner(owner);
        a.setRemark(remark);
        if (enabled != null) a.setEnabled(enabled);
        return a;
    }
}
