package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppInfoEntity;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;

@Data
public class UpdateAppRequestBO {

    @NotBlank private String appId;
    /** 旧调用方未传时回退 appName，保证前后端滚动发布期间不因新增字段失败。 */
    private String systemName;
    @NotBlank private String appName;
    @NotBlank private String sysCode;
    @NotBlank private String appCode;
    @NotBlank private String owner;
    private String remark;
    private Boolean enabled;

    public AppInfoEntity toEntity() {
        var a = new AppInfoEntity();
        a.setSystemName(systemName == null || systemName.isBlank() ? appName : systemName);
        a.setAppName(appName);
        a.setSysCode(sysCode);
        a.setAppCode(appCode);
        a.setOwner(owner);
        a.setRemark(remark);
        if (enabled != null) a.setEnabled(enabled);
        return a;
    }
}
