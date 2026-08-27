package com.pab.ficc.ibp.modelgate.server.domain.bo;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.LocalDateTime;

/** 修改单条服务授权（配额 / 启停 / 过期时间）。 */
@Data
public class UpdateServiceAuthRequestBO {

    @NotBlank(message = "接入系统 ID 不能为空")
    private String appId;

    @NotBlank(message = "服务编码不能为空")
    private String serviceId;

    private Boolean enabled;
    private Integer qpsLimit;
    private Integer dailyLimit;

    /**
     * 授权过期时间。
     *
     * <p>null 的语义是<b>「改为永久有效」</b>，不是「保持原值」——
     * 页面上的日期框清空就是要取消过期限制；若解释成保持原值，运营会发现"清不掉"。
     */
    private LocalDateTime expiresAt;
}
