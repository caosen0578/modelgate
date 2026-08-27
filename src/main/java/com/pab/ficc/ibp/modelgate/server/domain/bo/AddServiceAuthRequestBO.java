package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppServiceAuthEntity;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 批量授权入参：一个接入系统 × 多个服务，共用同一套配额模板。
 *
 * <p>形态对齐模型侧的 {@code AddAuthRequestBO}——管理页上运营是"勾一批服务、填一次限额"，
 * 逐条提交会让页面要发 N 个请求，且中途失败时状态不一致。
 */
@Data
public class AddServiceAuthRequestBO {

    @NotBlank(message = "接入系统 ID 不能为空")
    private String appId;

    @NotEmpty(message = "至少选择一个服务")
    private List<String> serviceIds;

    /** 每秒请求数上限，0 或 null = 不限 */
    private Integer qpsLimit;
    /** 每日请求数上限，0 或 null = 不限 */
    private Integer dailyLimit;
    /** 授权过期时间，null = 永久 */
    private LocalDateTime expiresAt;

    /** 把配额模板摊成实体，serviceId 由调用方逐个填。 */
    public AppServiceAuthEntity toTemplate() {
        AppServiceAuthEntity t = new AppServiceAuthEntity();
        t.setAppId(appId);
        t.setEnabled(Boolean.TRUE);
        t.setQpsLimit(qpsLimit == null ? 0 : qpsLimit);
        t.setDailyLimit(dailyLimit == null ? 0 : dailyLimit);
        t.setExpiresAt(expiresAt);
        return t;
    }
}
