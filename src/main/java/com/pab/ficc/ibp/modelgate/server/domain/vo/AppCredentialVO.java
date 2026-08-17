package com.pab.ficc.ibp.modelgate.server.domain.vo;

import com.pab.ficc.ibp.modelgate.server.domain.entity.AppCredentialEntity;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 凭证列表视图 — 屏蔽 secretHash / secretEncrypted 等敏感字段
 */
@Data
public class AppCredentialVO {

    private Long          id;
    private String        credName;
    private String        credType;
    /** ak_sk 类型为 AK 明文；api_key 类型为密钥末 4 位（仅供展示辨识） */
    private String        accessKey;
    private Boolean       enabled;
    private LocalDateTime expiresAt;
    private String        remark;
    private LocalDateTime createTime;

    /**
     * 明文是否可找回。
     *
     * <p>只暴露"有没有"这个布尔位，**不暴露密文本身**（见 05 §4.1 禁令）。
     * 启用「密钥可查看」之前创建的 api_key 凭证该位为 false，前端据此置灰查看按钮，
     * 避免用户点了才发现找不回。
     */
    private Boolean       secretRetrievable;

    public static AppCredentialVO of(AppCredentialEntity e) {
        AppCredentialVO vo = new AppCredentialVO();
        vo.id        = e.getId();
        vo.credName  = e.getCredName();
        vo.credType  = e.getCredType();
        vo.accessKey = e.getAccessKey();   // secretHash / secretEncrypted 不暴露
        vo.enabled   = e.getEnabled();
        vo.expiresAt = e.getExpiresAt();
        vo.remark    = e.getRemark();
        vo.createTime = e.getCreateTime();
        vo.secretRetrievable = e.getSecretEncrypted() != null && !e.getSecretEncrypted().isBlank();
        return vo;
    }
}
