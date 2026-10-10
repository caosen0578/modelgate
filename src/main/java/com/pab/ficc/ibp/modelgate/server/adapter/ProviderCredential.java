package com.pab.ficc.ibp.modelgate.server.adapter;

import com.pab.ficc.ibp.modelgate.server.domain.entity.ModelCredentialEntity;

/** 只读凭证视图；禁止默认 record/toString 把密钥拼入日志。 */
public final class ProviderCredential {
    private final String authType;
    private final String accessKey;
    private final String secretKey;
    private final String extraConfig;
    private ProviderCredential(ModelCredentialEntity entity) {
        authType = entity == null || entity.getAuthType() == null ? "none" : entity.getAuthType();
        accessKey = entity == null ? null : entity.getAccessKey();
        secretKey = entity == null ? null : entity.getSecretKey();
        extraConfig = entity == null ? null : entity.getExtraConfig();
    }
    public static ProviderCredential from(ModelCredentialEntity entity) { return new ProviderCredential(entity); }
    public String authType() { return authType; }
    public String accessKey() { return accessKey; }
    public String secretKey() { return secretKey; }
    public String extraConfig() { return extraConfig; }
    ModelCredentialEntity legacyCredential() {
        var entity = new ModelCredentialEntity();
        entity.setAuthType(authType); entity.setAccessKey(accessKey);
        entity.setSecretKey(secretKey); entity.setExtraConfig(extraConfig);
        return entity;
    }
    @Override public String toString() { return "ProviderCredential[authType=" + authType + ", secrets=REDACTED]"; }
}
