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
    private String        accessKey;   // AK/SK 类型才有值，api_key 类型此处为 null
    private Boolean       enabled;
    private LocalDateTime expiresAt;
    private String        remark;
    private LocalDateTime createTime;

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
        return vo;
    }
}
