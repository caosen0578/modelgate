package com.pab.ficc.ibp.modelgate.server.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
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

    // ===== 内部规范基础字段（修改人 X-Admin-User 透传写入 updater） =====
    //
    // 本表**不含 is_del、不做逻辑删除**（与其余 6 张管理表的约定不同，是有意为之）：
    // config_key 即主键，逻辑删除后行仍在，再存同名 key 会因 selectById 被过滤而走 insert，
    // 直接主键冲突——配置项删了就加不回来。且配置不是业务数据，无留痕需求
    // （要追溯有 updater / update_time，变更本身也进审计）。故改为物理删除。
    @TableField(fill = FieldFill.INSERT)
    private String creater;
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updater;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
    @TableField(fill = FieldFill.INSERT)
    private String createrUm;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updaterUm;

    // ===== 内置 Key 常量，避免硬编码散落各处 =====

    // 注：原 KEY_UPSTREAM_URL("upstream.url") 已移除。
    // 真实转发地址按模型动态取自 mg_model.upstream_url（见 ModelRouteFilter），
    // 该全局配置从未参与路由，属早期"整网关转发到单一上游"设计的残留。


    /** 熔断开关 */
    public static final String KEY_CIRCUIT_BREAKER_ENABLED = "circuit.breaker.enabled";

}
