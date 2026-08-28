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
 * 通用服务注册（{@code mg_service}）——{@code /svc/{serviceId}/**} 流量的上游目标。
 *
 * <p>与 {@code ModelInstanceEntity} 平级但**刻意分表**：模型有 api_spec / default_params /
 * force_params / param_mapping / upstreamModelId 一整套专属字段，服务一个都用不上；
 * 两者路由键也本质不同（模型在 body 的 model 字段，服务在 path），
 * 合表迟早要靠 {@code if (targetType)} 分叉。详见 spec/modelgate/12 §1.1。
 */
@Data
@TableName("mg_service")
public class ServiceEntity {
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 服务编码，对外路径即 {@code /svc/{serviceId}/**}。
     *
     * <p><b>一经创建不可修改</b>：它是 URL 的一部分，改名等于换 URL、接入方必须同步改代码——
     * 那是"新建 + 下线"，不是"改名"。这与 {@code mg_model.model_id} 不同，
     * 后者是请求体里的协议字段、支持 ON UPDATE CASCADE 改名。
     *
     * <p>格式约束 {@code ^[a-z0-9][a-z0-9-]{1,31}$} 由 {@code ServiceRegistryService} 校验。
     */
    private String serviceId;
    private String serviceName;
    /** 所属业务系统中文名，仅展示用，不参与任何判定 */
    private String ownerSystem;
    /** 上游地址（协议+域名+端口[+固定前缀路径]），末尾斜杠在写入时被规范化去掉 */
    private String upstreamUrl;

    /**
     * 是否剥掉 {@code /svc/{serviceId}} 再转发。
     *
     * <p>{@code true}（默认）：{@code /svc/crm/api/v2/query} → {@code {upstreamUrl}/api/v2/query}，
     * 接入方内部接口路径一行都不用改。
     * {@code false}：完整路径原样转发，供上游确实需要看到 {@code /svc/{编码}} 的场景。
     */
    private Boolean stripPrefix;

    /**
     * 单请求超时（毫秒），0 = 沿用全局 {@code httpclient.response-timeout}。
     *
     * <p>通过 Route metadata 的 {@code response-timeout} 下发给 {@code NettyRoutingFilter}
     * （该 key 已在其常量池中确认）。
     *
     * <p>⚠️ 真实语义是<b>「等上游吐出响应头的最长时间」，管不到响应体</b>——
     * SCG 从不调用 {@code HttpClient.responseTimeout(...)}，这个值只作用于
     * NettyRoutingFilter 里那层 {@code Flux.timeout}，而它包住的 Flux 在响应头到达时就完成。
     * 所以对 SSE 服务它基本不起作用，对非流式接口它才是真正的超时。详见 12 §11.1。
     *
     * <p>注意与 {@code mg_model.timeout_ms} 的区别：那个字段**只被管理面连通性探测使用**，
     * 转发热路径从不读取；本字段是真正生效的。
     */
    private Integer timeoutMs;

    /** 是否 SSE 服务：影响管理面展示与熔断配置选择，**不影响转发本身**（转发一律流式透传） */
    private Boolean sse;

    /**
     * 连通性探测使用的 HTTP 方法，默认 {@code GET}。
     *
     * <p>为什么要可配：接入的服务接口各有各的请求方式——有的只认 POST，有的根路径直接 404。
     * 固定 GET 打根路径的探测结果对这类服务没有参考价值，所以改为录入时一并登记。
     *
     * <p>⚠️ <b>选非 GET 意味着探测会对上游产生真实副作用</b>：通用服务的写接口可能真的
     * 建一条数据出来。管理页面对非 GET 会显著警告，但最终由录入者决定——
     * 网关无法替业务判断哪个接口是幂等的。
     */
    private String probeMethod;

    /** 探测路径，如 {@code /health}；留空则探测上游根路径 */
    private String probePath;

    /** 探测请求体（JSON），仅 {@link #probeMethod} 需要请求体时使用 */
    private String probeBody;

    /**
     * 报文留档开关。
     *
     * <p>放在服务本身而非授权行——与模型侧不同。模型侧放授权行是因为不同接入系统合规要求不同；
     * 服务侧的诉求是"每个接口录入时决定记不记"，粒度在接口本身，放这里更贴合，
     * 且热路径上少一次按 (appId, serviceId) 的查询。
     */
    private Boolean payloadLogEnabled;

    private String description;
    private Boolean enabled;

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

    /** 上游地址去掉末尾斜杠，避免与请求剩余路径拼出 {@code //}。写入时已规范化，此处是读取侧兜底。 */
    public String normalizedUpstreamUrl() {
        return upstreamUrl == null ? null : upstreamUrl.replaceAll("/+$", "");
    }

    /** 探测方法归一化：空值按 GET（与建表默认值一致，也是最安全的选择——GET 无副作用）。 */
    public String resolveProbeMethod() {
        return probeMethod == null || probeMethod.isBlank() ? "GET" : probeMethod.trim().toUpperCase();
    }

    /** {@link #stripPrefix} 为 null 时按 true 处理（老数据兜底），与建表默认值一致。 */
    public boolean shouldStripPrefix() {
        return !Boolean.FALSE.equals(stripPrefix);
    }
}
