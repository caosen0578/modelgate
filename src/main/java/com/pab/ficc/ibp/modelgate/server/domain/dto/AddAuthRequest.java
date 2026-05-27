package com.pab.ficc.ibp.modelgate.server.domain.dto;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuth;
import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class AddAuthRequest {

    @NotBlank  private String appId;
    @NotEmpty  private List<String> modelIds;
    private Integer qpsLimit     = 0;
    private Integer dailyLimit   = 0;
    private Long    monthlyTokens = 0L;
    private LocalDateTime expiresAt;

    public AppModelAuth toTemplate() {
        var a = new AppModelAuth();
        a.setQpsLimit(qpsLimit != null ? qpsLimit : 0);
        a.setDailyLimit(dailyLimit != null ? dailyLimit : 0);
        a.setMonthlyTokens(monthlyTokens != null ? monthlyTokens : 0L);
        a.setExpiresAt(expiresAt);
        return a;
    }
}
