package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class CreateAppRequestBO {

    @NotBlank private String appId;
    @NotBlank private String appName;
    @NotBlank private String sysCode;
    @NotBlank private String appCode;
    @NotBlank private String owner;
    private String remark;

    public AppInfoEntity toEntity() {
        var a = new AppInfoEntity();
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
