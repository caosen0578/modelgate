package com.pab.ficc.ibp.modelgate.server.domain.dto;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredential;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import java.time.LocalDateTime;

@Data
public class CreateCredentialRequest {

    @NotBlank private String appId;
    @NotBlank private String credName;
    @NotBlank private String credType;
    private LocalDateTime expiresAt;
    private String remark;

    public AppCredential toEntity() {
        var c = new AppCredential();
        c.setCredName(credName);
        c.setCredType(credType);
        c.setExpiresAt(expiresAt);
        c.setRemark(remark);
        return c;
    }
}
