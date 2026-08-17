package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuthEntity;
import lombok.Data;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class AddAuthRequestBO {

    @NotBlank  private String appId;
    @NotEmpty  private List<String> modelIds;
    private Integer qpsLimit     = 0;
    private Integer dailyLimit   = 0;
    private Long    monthlyTokens = 0L;
    /** 请求/响应报文记录开关，默认关闭（报文体积大，需显式开启） */
    private Boolean payloadLogEnabled = Boolean.FALSE;
    private LocalDateTime expiresAt;

    public AppModelAuthEntity toTemplate() {
        var a = new AppModelAuthEntity();
        a.setQpsLimit(qpsLimit != null ? qpsLimit : 0);
        a.setDailyLimit(dailyLimit != null ? dailyLimit : 0);
        a.setMonthlyTokens(monthlyTokens != null ? monthlyTokens : 0L);
        a.setPayloadLogEnabled(Boolean.TRUE.equals(payloadLogEnabled));
        a.setExpiresAt(expiresAt);
        return a;
    }
}
