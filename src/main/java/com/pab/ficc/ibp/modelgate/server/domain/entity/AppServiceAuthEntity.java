package com.pab.ficc.ibp.modelgate.server.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 接入系统对通用服务的调用授权（{@code mg_app_service_auth}）。
 *
 * <p>与 {@code AppModelAuthEntity} 平级、互不影响：同一个 appId 的同一把凭证
 * 既能调模型也能调服务，**凭证鉴权完全共用**，真正决定"能不能调这个服务"的是本表。
 *
 * <p>刻意<b>没有 monthlyTokens</b>：服务不产生 Token，该维度对它无意义。
 * {@code RateLimitService.checkAllQuotas} 的月度参数传 0，方法内 ≤0 直接跳过、
 * 不发 Redis 命令——所以服务流量稳态下最多 2 次 Redis 往返，比模型侧还少一次。
 */
@Data
@TableName("mg_app_service_auth")
public class AppServiceAuthEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String appId;
    private String serviceId;
    private Boolean enabled;
    /** 每秒请求数上限，0=不限 */
    private Integer qpsLimit;
    /** 每日请求数上限，0=不限 */
    private Integer dailyLimit;
    /** 授权过期时间，NULL=永久授权（过期等同禁用） */
    private LocalDateTime expiresAt;

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
     * <p><b>字段默认值 "0" 是刻意的兜底，不得删除</b>——理由与 {@code ModelInstanceEntity}
     * 上那段完全一致：{@code strictInsertFill} 只在值为 null 时填充，一旦 MetaObjectHandler
     * 未被装配，MyBatis-Plus 会把本字段以显式 NULL 写进 INSERT，而 {@link TableLogic} 的
     * {@code WHERE is_del='0'} 匹配不到 NULL —— 整行插入成功却永远查不出来，且毫无报错。
     */
    @TableLogic(value = "0", delval = "1")
    @TableField(fill = FieldFill.INSERT)
    private String isDel = "0";
    @TableField(fill = FieldFill.INSERT)
    private String createrUm;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private String updaterUm;

    /** 授权是否实际生效：启用 且 未过期。系统本身是否启用由调用方另行判断。 */
    public boolean isEffective() {
        return !Boolean.FALSE.equals(enabled)
                && (expiresAt == null || expiresAt.isAfter(LocalDateTime.now()));
    }
}
