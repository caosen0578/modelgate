package com.pab.ficc.ibp.modelgate.server.domain.bo;

import lombok.Data;

/** 创建/更新模型时附带的上游凭证信息 */
@Data
public class CredentialRequestBO {

    private String authType;
    private String accessKey;
    private String secretKey;
    private String extraConfig;
}
