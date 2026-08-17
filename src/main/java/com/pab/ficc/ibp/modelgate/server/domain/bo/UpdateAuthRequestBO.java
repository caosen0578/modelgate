package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDateTime;

@Data
public class UpdateAuthRequestBO {

    @NotBlank private String appId;
    @NotBlank private String modelId;
    private Integer qpsLimit     = 0;
    private Integer dailyLimit   = 0;
    private Long    monthlyTokens = 0L;
    /** 请求/响应报文记录开关，默认关闭（报文体积大，需显式开启） */
    private Boolean payloadLogEnabled = Boolean.FALSE;
    private LocalDateTime expiresAt;

    public AppModelAuthEntity toEntity() {
        var a = new AppModelAuthEntity();
        a.setQpsLimit(qpsLimit != null ? qpsLimit : 0);
        a.setDailyLimit(dailyLimit != null ? dailyLimit : 0);
        a.setMonthlyTokens(monthlyTokens != null ? monthlyTokens : 0L);
        a.setPayloadLogEnabled(Boolean.TRUE.equals(payloadLogEnabled));
        a.setExpiresAt(expiresAt);
        return a;
    }
}
