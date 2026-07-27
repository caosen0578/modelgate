package com.pab.ficc.ibp.modelgate.server.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("mg_gateway_config")
public class GatewayConfigEntity {

    @TableId(type = IdType.INPUT)
    private String configKey;

    /** 配置 value */
    private String configValue;

    /** 描述说明 */
    private String description;

    /** 最后修改人（从 X-Admin-User 透传，业务语义保留） */
    private String updatedBy;

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

    // ===== 内置 Key 常量，避免硬编码散落各处 =====

    /** 上游模型服务地址 */
    public static final String KEY_UPSTREAM_URL = "upstream.url";

    /** 鉴权总开关（true=开启，false=放行所有） */
    public static final String KEY_AUTH_ENABLED = "auth.enabled";

    /** 熔断开关 */
    public static final String KEY_CIRCUIT_BREAKER_ENABLED = "circuit.breaker.enabled";

    /** 默认限流（每秒） */
    public static final String KEY_DEFAULT_RATE_LIMIT = "rate.limit.default";
}
