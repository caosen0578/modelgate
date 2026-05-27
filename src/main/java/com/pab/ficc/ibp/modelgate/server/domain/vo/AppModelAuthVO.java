package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppModelAuth;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AppModelAuthVO {

    private String        appId;
    private String        modelId;
    private Boolean       enabled;
    private Integer       qpsLimit;
    private Integer       dailyLimit;
    private Long          monthlyTokens;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;

    public static AppModelAuthVO of(AppModelAuth e) {
        AppModelAuthVO vo = new AppModelAuthVO();
        vo.appId         = e.getAppId();
        vo.modelId       = e.getModelId();
        vo.enabled       = e.getEnabled();
        vo.qpsLimit      = e.getQpsLimit();
        vo.dailyLimit    = e.getDailyLimit();
        vo.monthlyTokens = e.getMonthlyTokens();
        vo.expiresAt     = e.getExpiresAt();
        vo.createdAt     = e.getCreatedAt();
        return vo;
    }
}
