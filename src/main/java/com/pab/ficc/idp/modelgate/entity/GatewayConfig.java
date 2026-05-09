package com.pab.ficc.idp.modelgate.entity;

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
public class GatewayConfig {

    @TableId(type = com.baomidou.mybatisplus.annotation.IdType.INPUT)
    private String configKey;

    /** 配置 value */
    private String configValue;

    /** 描述说明 */
    private String description;

    /** 最后修改人（从 X-Admin-User 透传） */
    private String updatedBy;

    /** 更新时间 */
    private LocalDateTime updatedAt;

    /** 创建时间 */
    private LocalDateTime createdAt;

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
