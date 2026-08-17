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
    /**
     * 删除标志(1-已删除 0-未删除)，逻辑删除。
     *
     * <p><b>字段默认值 "0" 是刻意的兜底，不得删除</b>：
     * 本字段虽标了 {@code fill = INSERT}，但 {@code strictInsertFill} <b>只在值为 null 时才填充</b>，
     * 一旦 MetaObjectHandler 未被装配（如自定义 SqlSessionFactory 忘了挂 GlobalConfig），
     * MyBatis-Plus 仍会把本字段以 <b>显式 NULL</b> 写进 INSERT，覆盖掉建表时的 {@code DEFAULT '0'}。
     * 而 {@link TableLogic}(value="0") 让每条查询都带 {@code WHERE is_del='0'}，
     * SQL 里 '0' 匹配不到 NULL —— 结果是<b>整行插入成功却永远查不出来</b>，且毫无报错。
     * 这个坑真实发生过（见 spec/modelgate/11 §17）。
     *
     * <p>给了默认值后，本字段的正确性不再依赖任何框架装配；
     * handler 仍会填 create_time / updater 等其余字段，那些缺失只影响审计信息、不会让数据消失。
     */
    @TableLogic(value = "0", delval = "1")
    @TableField(fill = FieldFill.INSERT)
    private String isDel = "0";
    @TableField(fill = FieldFill.INSERT)
    private String createrUm;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updaterUm;
}
