package com.pab.ficc.ibp.modelgate.server.domain.vo;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 凭证创建成功响应 — 明文 secret 仅此一次返回
 */
@Data
@Builder
public class CredentialCreatedVO {

    private Long   id;
    private String credType;

    /** api_key 类型：完整密钥，仅在创建时返回 */
    private String apiKey;

    /** ak_sk 类型：Access Key（不含 secret） */
    private String accessKey;

    /** ak_sk 类型：Secret Key 明文，仅在创建时返回 */
    private String secretKey;

    private LocalDateTime expiresAt;
}
