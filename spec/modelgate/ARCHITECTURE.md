# ModelGate · 技术架构文档

> **文档版本**：v1.0  
> **更新日期**：2026-04-23

---

## 模块划分

```
ModelGate
├── gateway/          网关核心（Spring Cloud Gateway + WebFlux）
│   ├── filter/       过滤器链
│   │   ├── CredentialAuthFilter      凭证鉴权（-200）
│   │   ├── AppAccessControlFilter    访问控制（-190）
│   │   ├── ModelAuthFilter           模型授权校验（-180）
│   │   ├── RateLimitFilter           限流（-170）
│   │   ├── TraceFilter               链路追踪注入（-160）
│   │   ├── ModelRouteFilter          路由 & 参数转换（-150）
│   │   └── AuditFilter               审计日志（最后执行）
│   └── adapter/      上游适配器
│       ├── OpenAIAdapter
│       ├── AzureOpenAIAdapter
│       ├── AnthropicAdapter
│       └── CustomAdapter
│
├── admin/            管理后台 API（Spring MVC 或 WebFlux 同服务）
│   ├── model/        模型管理
│   ├── app/          接入系统管理
│   ├── credential/   凭证管理
│   ├── auth/         模型授权管理
│   └── access/       访问控制管理
│
├── common/           公共模块
│   ├── crypto/       密钥加解密
│   ├── cache/        缓存策略
│   └── audit/        审计日志
│
└── spec/ModelGate/       产品与架构文档
```

## 过滤器执行顺序

```
请求进入
    │
    ▼  order = -200
CredentialAuthFilter
  - API Key: Redis 查凭证 → 本地缓存
  - AK/SK: HMAC 签名验证 + 时间戳防重放
    │ 401 → 拒绝
    ▼  order = -190
AppAccessControlFilter
  - 校验客户端 IP 是否在白名单
  - 校验 Origin/Referer 域名是否允许
    │ 403 → 拒绝
    ▼  order = -180
ModelAuthFilter
  - 解析请求 body 中的 model 字段
  - 校验该 App 是否有权限访问该 model
  - 校验授权是否启用 + 是否过期
    │ 403 → 拒绝
    ▼  order = -170
RateLimitFilter
  - App 级 QPS（Redis Lua 令牌桶）
  - App+Model 级 QPS
    │ 429 → 拒绝
    ▼  order = -160
TraceFilter
  - 生成/透传 traceId
  - 注入 MDC
    ▼  order = -150
ModelRouteFilter
  - 查询模型上游配置
  - 参数转换（OpenAI → 上游格式）
  - 注入上游鉴权 Header
  - 设置 GATEWAY_REQUEST_URL_ATTR
    ▼
CircuitBreakerFilter（Resilience4j）
    ▼
Spring Cloud Gateway 路由转发
    ▼
上游模型服务（SSE 流式透传）
    │
    ▼
AuditFilter（doFinally）
  - 异步写 Kafka 审计日志
  - 更新调用计数
```

## 缓存设计

| 缓存内容 | 存储 | TTL | 失效策略 |
|---|---|---|---|
| 凭证信息（App + 权限） | Caffeine L1 + Redis L2 | 60s | 写入时主动失效 |
| 模型配置（上游地址等） | Caffeine L1 | 30s | 定时刷新 |
| IP 白名单 | Caffeine L1 | 60s | 写入时主动失效 |
| 限流计数器 | Redis | 1s（滑动窗口） | 自动过期 |
| 日调用量计数 | Redis | 至当天 24:00 | 每日 0 点重置 |

## 密钥安全方案

```
存储：AES-256-GCM 加密后入库
      加密密钥通过环境变量注入（或对接 KMS）

读取：网关启动时解密加载到内存
      内存中明文，不写任何日志

展示：Secret Key / API Key 仅在创建时响应中返回一次
      数据库中存哈希摘要（SHA-256），用于管理列表展示末四位
```
