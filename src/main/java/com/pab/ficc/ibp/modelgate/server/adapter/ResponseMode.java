package com.pab.ficc.ibp.modelgate.server.adapter;

import com.pab.ficc.ibp.modelgate.server.common.BusinessException;
import com.pab.ficc.ibp.modelgate.server.common.ErrorCode;

/** 存量配置缺省时严格保持原生响应。 */
public enum ResponseMode {
    PASSTHROUGH, OPENAI_COMPATIBLE;

    public static ResponseMode resolve(String value) {
        if (value == null || value.isBlank()) return PASSTHROUGH;
        try { return valueOf(value); }
        catch (IllegalArgumentException e) {
            throw new BusinessException(ErrorCode.PROVIDER_CONFIG_INVALID, "responseMode 无效");
        }
    }
}
