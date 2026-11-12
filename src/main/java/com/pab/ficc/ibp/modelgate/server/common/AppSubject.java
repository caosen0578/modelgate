package com.pab.ficc.ibp.modelgate.server.common;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

/** 接入主体和内部编号约定。是否个人以存储类型判定，而非 appId 前缀。 */
public final class AppSubject {
    public static final String SYSTEM = "SYSTEM";
    public static final String PERSONAL = "PERSONAL";
    public static final String PERSONAL_PREFIX = "P_";
    public static final String PERSONAL_ENABLED_PROPERTY = "${modelgate.personal-access.enabled:false}";
    public static final int UM_MAX_LENGTH = 64;
    private static final Pattern UM_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

    private AppSubject() {}

    public static String newPersonalAppId() {
        return PERSONAL_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    public static boolean isReservedAppId(String appId) {
        return appId != null && appId.strip().regionMatches(true, 0, PERSONAL_PREFIX, 0, PERSONAL_PREFIX.length());
    }

    public static String normalizeUm(String um) {
        String value = um == null ? "" : um.strip();
        if (!UM_PATTERN.matcher(value).matches()) {
            throw new BusinessException(ErrorCode.INVALID_PARAMETER, "UM 应为 1–64 位字母、数字或 ._-，且以字母或数字开头");
        }
        return value.toUpperCase(Locale.ROOT);
    }
}
