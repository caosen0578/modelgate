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
 * 服务上游鉴权凭证（{@code mg_service_credential}，与 {@code mg_service} 一对一）。
 *
 * <p>网关的语义是<b>凭证替换</b>：调用方带 ModelGate 的凭证进来，网关剔除它、
 * 换成本表里配置的上游凭证再转发。剔除那一步由 {@code UpstreamHeaderBuilder} 负责，
 * <b>不能省</b>——{@code none} 与 {@code custom_header} 这两种 authType 根本不写
 * {@code Authorization}，不显式剔除的话网关凭证会直接散给上游系统。
 */
@Data
@TableName("mg_service_credential")
public class ServiceCredentialEntity {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String serviceId;

    /**
     * 上游鉴权类型，决定注入哪些请求头：
     * <ul>
     *   <li>{@code none}          —— 不注入（上游在内网且自身不鉴权）</li>
     *   <li>{@code api_key}       —— {@code Authorization: Bearer {secretKey}}</li>
     *   <li>{@code ak_sk}         —— {@code X-Access-Key} + {@code X-Secret-Key}</li>
     *   <li>{@code custom_header} —— 仅注入 {@link #extraHeaders}</li>
     * </ul>
     * 四种情况都会**额外**注入 {@link #extraHeaders}（若非空）。
     */
    private String authType;

    /** AK（明文）；{@code custom_header} / {@code none} 时不使用 */
    private String accessKey;
    /** SK/密钥，加密存储（AES-256-GCM，CryptoService）；读取侧按需解密，列表接口不回显 */
    private String secretKey;

    /**
     * 附加上游请求头（JSON 对象），**整体加密存储**。
     *
     * <p>加密而非明文：这些头里经常混着签名密钥、租户令牌一类的敏感值。
     * 形如 {@code {"X-Tenant":"ficc","X-Channel":"gateway"}}，值必须都是字符串。
     */
    private String extraHeaders;

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
}
