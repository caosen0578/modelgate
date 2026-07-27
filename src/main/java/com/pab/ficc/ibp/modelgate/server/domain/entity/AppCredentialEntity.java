package com.pab.ficc.ibp.modelgate.server.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("mg_app_credential")
public class AppCredentialEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    private String credName;
    private String credType;
    private String accessKey;
    private String secretHash;
    private String secretEncrypted;
    private Boolean enabled;
    private LocalDateTime expiresAt;
    private String remark;

    // ===== 内部规范基础字段 =====
    @TableField(fill = FieldFill.INSERT)
    private String creater;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updater;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    /** 删除标志(1-已删除 0-未删除)，逻辑删除 */
    @TableLogic(value = "0", delval = "1")
    @TableField(fill = FieldFill.INSERT)
    private String isDel;
    @TableField(fill = FieldFill.INSERT)
    private String createrUm;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updaterUm;
}
