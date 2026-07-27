package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredentialEntity;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

@Data
public class CreateCredentialRequestBO {

    @NotBlank private String appId;
    @NotBlank private String credName;
    @NotBlank private String credType;
    private LocalDateTime expiresAt;
    private String remark;

    public AppCredentialEntity toEntity() {
        var c = new AppCredentialEntity();
        c.setCredName(credName);
        c.setCredType(credType);
        c.setExpiresAt(expiresAt);
        c.setRemark(remark);
        return c;
    }
}
