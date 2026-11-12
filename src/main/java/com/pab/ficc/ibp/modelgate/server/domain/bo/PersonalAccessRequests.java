package com.pab.ficc.ibp.modelgate.server.domain.bo;

import com.pab.ficc.ibp.modelgate.server.common.AppSubject;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** 个人管理入参只以 UM 定位，不接受调用方指定内部 appId 或改变主体类型。 */
public final class PersonalAccessRequests {
    private PersonalAccessRequests() {}

    public record Profile(
            @NotBlank @Size(max = AppSubject.UM_MAX_LENGTH) String um,
            @NotBlank @Size(max = 64) String name,
            @Size(max = 512) String remark) {}

    public record Credential(
            @NotBlank @Size(max = AppSubject.UM_MAX_LENGTH) String um,
            @NotBlank @Size(max = 128) String credName,
            LocalDateTime expiresAt) {}

    public record ModelGrant(
            @NotBlank @Size(max = AppSubject.UM_MAX_LENGTH) String um,
            @NotBlank @Size(max = 64) String modelId,
            @PositiveOrZero Integer qpsLimit,
            @PositiveOrZero Integer dailyLimit,
            @PositiveOrZero @Max(9_007_199_254_740_991L) Long monthlyTokens,
            LocalDateTime expiresAt) {}
}
