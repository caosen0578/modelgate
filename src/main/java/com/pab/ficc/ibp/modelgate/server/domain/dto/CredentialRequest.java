package com.pab.ficc.ibp.modelgate.server.domain.dto;

import lombok.Data;

/** 创建/更新模型时附带的上游凭证信息 */
@Data
public class CredentialRequest {

    private String authType;
    private String accessKey;
    private String secretKey;
    private String extraConfig;
}
