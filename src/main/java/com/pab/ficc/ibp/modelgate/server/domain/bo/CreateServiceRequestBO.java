package com.pab.ficc.ibp.modelgate.server.domain.bo;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.Data;

/**
 * 新增服务入参：服务本身 + 上游凭证，一次提交、事务写两张表。
 *
 * <p>凭证与服务分表但同表单提交——与模型侧 {@code CreateModelRequestBO} 同一形态。
 * 分开提交会出现「服务建好了但凭证没配」的中间态，那时请求已经能打到上游、只是没带凭证，
 * 排查起来像上游故障。
 */
@Data
public class CreateServiceRequestBO {

    /**
     * 服务编码，对外路径即 {@code /svc/{serviceId}/**}。
     *
     * <p>正则在这里再钉一遍（Service 层也校验）：编码是 URL 的一部分，
     * 放进去一个带斜杠或大写的值会直接让路由分段错乱，且创建后不可修改、只能删了重建。
     */
    @NotBlank(message = "服务编码不能为空")
    @Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,31}$",
             message = "服务编码只能是小写字母、数字、连字符，2~32 位且不能以连字符开头")
    private String serviceId;

    @NotBlank(message = "服务名称不能为空")
    private String serviceName;

    private String ownerSystem;

    @NotBlank(message = "上游地址不能为空")
    private String upstreamUrl;

    /** null 按 true 处理（剥掉 /svc/{编码} 再转发） */
    private Boolean stripPrefix;

    /** 0 或 null = 沿用全局 response-timeout */
    @PositiveOrZero(message = "超时时间不能为负")
    private Integer timeoutMs;

    private Boolean sse;
    private Boolean payloadLogEnabled;
    private String description;
    private Boolean enabled;

    // ===== 上游凭证（写 mg_service_credential）=====
    /** none | api_key | ak_sk | custom_header；留空按 none */
    private String authType;
    private String accessKey;
    /** 明文 SK，落库前由 CryptoService 加密 */
    private String secretKey;
    /** JSON 对象字符串，如 {"X-Tenant":"ficc"}；落库前整体加密 */
    private String extraHeaders;
}
