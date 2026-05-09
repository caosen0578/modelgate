# ModelGate · 内部 AI 模型网关平台 · 产品需求文档

> **文档版本**：v1.0  
> **状态**：草稿  
> **更新日期**：2026-04-23

---

## 目录

1. [产品定位](#1-产品定位)
2. [整体架构](#2-整体架构)
3. [核心概念与数据模型](#3-核心概念与数据模型)
4. [功能模块详述](#4-功能模块详述)
   - 4.1 [模型管理](#41-模型管理)
   - 4.2 [接入系统管理](#42-接入系统管理)
   - 4.3 [授权管理](#43-授权管理)
   - 4.4 [访问控制](#44-访问控制)
   - 4.5 [流量转发与路由](#45-流量转发与路由)
   - 4.6 [API 规范适配](#46-api-规范适配)
   - 4.7 [监控与审计](#47-监控与审计)
5. [管理后台页面清单](#5-管理后台页面清单)
6. [API 接口规范](#6-api-接口规范)
7. [数据库表设计](#7-数据库表设计)
8. [非功能性需求](#8-非功能性需求)
9. [待确认事项](#9-待确认事项)

---

## 1. 产品定位

**ModelGate** 是一个面向企业内部的 AI 模型流量网关平台。

### 核心角色

```
[外部模型服务商]          [ModelGate 平台]              [内部业务系统]
 OpenAI / Claude  ←→   模型注册 & 鉴权适配   ←→   系统A / 系统B / 系统C
 Azure / 私有化部署       流量路由 & 参数转换        统一 API / 订阅授权
                         访问控制 & 计量审计          IP/域名白名单
```

### 核心价值

| 价值点 | 说明 |
|---|---|
| **统一入口** | 内部所有系统通过 ModelGate 访问模型，无需各自维护模型密钥 |
| **多模型管理** | 统一管理多个提供商、多个模型，随时切换上游无需改动业务代码 |
| **订阅授权** | 为每个接入系统颁发独立凭证，权限可精细控制 |
| **访问控制** | IP/域名白名单 + 授权开关，安全可管控 |
| **API 标准化** | 对外统一暴露 OpenAI 兼容格式，屏蔽上游差异 |
| **可观测性** | 调用量、延迟、错误率全面监控，费用估算 |

---

## 2. 整体架构

```
┌─────────────────────────────────────────────────────────┐
│                     ModelGate 平台                           │
│                                                          │
│  ┌──────────┐   ┌──────────────┐   ┌─────────────────┐  │
│  │ 管理后台  │   │  网关层(GW)  │   │   数据存储层     │  │
│  │ Admin UI │   │              │   │                 │  │
│  │          │   │ ① 认证鉴权   │   │ MySQL           │  │
│  │ 模型管理  │   │ ② 授权校验   │   │  - 模型配置     │  │
│  │ 系统授权  │   │ ③ 访问控制   │   │  - 接入系统     │  │
│  │ 访问控制  │   │ ④ 参数转换   │   │  - 授权关系     │  │
│  │ 监控大盘  │   │ ⑤ 流量路由   │   │  - 访问控制     │  │
│  └──────────┘   │ ⑥ 响应透传   │   │                 │  │
│                 └──────┬───────┘   │ Redis           │  │
│                        │           │  - 鉴权缓存      │  │
│                        │           │  - 限流计数      │  │
│                        │           │                 │  │
│                        │           │ Kafka           │  │
│                        │           │  - 审计日志      │  │
│                        │           └─────────────────┘  │
└────────────────────────┼────────────────────────────────┘
                         │
         ┌───────────────┼───────────────┐
         ▼               ▼               ▼
    [OpenAI API]   [Azure OpenAI]   [私有化模型]
                                    [Anthropic]
                                    [自定义上游]
```

### 请求完整流程

```
业务系统请求
    │
    ▼
① 提取凭证（API Key 或 AK/SK 签名验证）
    │
    ▼
② 查授权表：凭证是否有效 + 是否有权限访问目标模型
    │
    ▼
③ 访问控制：IP / 域名白名单校验
    │
    ▼
④ 限流检查：系统级 QPS / 模型级 QPS
    │
    ▼
⑤ 参数转换：OpenAI 格式 → 目标模型原生格式
    │
    ▼
⑥ 路由转发：根据 model 字段定位上游地址 + 注入模型鉴权凭证
    │
    ▼
⑦ 流式/非流式响应透传给业务系统
    │
    ▼
⑧ 异步记录审计日志（Kafka）
```

---

## 3. 核心概念与数据模型

### 3.1 核心实体关系

```
Model（模型）
  │  一个平台模型对应一个实际上游模型
  │
  └── ModelCredential（模型凭证）
        上游鉴权信息（API Key / AK/SK），与模型 1:1

ClientApp（接入系统）
  │  申请接入 ModelGate 的内部业务系统
  │
  ├── AppCredential（系统凭证）
  │     颁发给系统的 API Key 或 AK/SK，一个系统可有多个凭证
  │
  ├── AppModelAuth（系统-模型授权）
  │     系统被授权可访问哪些模型，含 QPS、配额等限制
  │
  └── AppAccessControl（访问控制）
        该系统允许的 IP 白名单 / 域名白名单
```

### 3.2 凭证类型说明

| 类型 | 适用场景 | 说明 |
|---|---|---|
| **API Key** | 简单场景 | 请求头 `Authorization: Bearer sk-xxx`，简单直接 |
| **AK/SK** | 安全要求高 | Access Key 公开，Secret Key 用于 HMAC-SHA256 签名请求，防重放 |

---

## 4. 功能模块详述

### 4.1 模型管理

#### 功能概述
管理员在此录入平台接入的所有模型，配置上游连接信息。

#### 模型基础信息

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| 模型名称 | string | ✅ | 平台内展示名，如"GPT-4o" |
| 模型 ID | string | ✅ | 对外暴露的模型标识，业务系统在请求中使用此 ID，如 `gpt-4o`、`claude-3-5-sonnet` |
| 提供商 | enum | ✅ | OpenAI / Azure OpenAI / Anthropic / 私有部署 / 自定义 |
| 模型描述 | string | ❌ | 描述模型能力、适用场景 |
| 模型类型 | enum | ✅ | 对话（Chat）/ 嵌入（Embedding）/ 图像（Image）/ 语音（Audio）|
| 状态 | enum | ✅ | 启用 / 禁用 / 维护中 |
| 上游地址 | string | ✅ | 实际请求的 URL，如 `https://api.openai.com/v1/chat/completions` |
| API 规范 | enum | ✅ | 见 4.6 节 |
| 超时时间(s) | int | ✅ | 上游请求超时，默认 300s（流式场景） |
| 最大上下文 | int | ❌ | Token 上限，用于前置校验提示 |
| 排序权重 | int | ❌ | 在下拉列表中的显示顺序 |

#### 上游鉴权配置

| 鉴权方式 | 所需字段 | 注入方式 |
|---|---|---|
| **API Key** | api_key | 请求头 `Authorization: Bearer {api_key}` |
| **AK/SK** | access_key + secret_key | Header 签名或 Query 签名（依提供商规范） |
| **Azure API Key** | api_key + resource_name + deployment_id + api_version | Azure 专属 Header + URL 格式 |
| **无鉴权** | — | 私有内网模型 |

#### 参数配置（模型级默认参数）

支持为每个模型配置默认参数，下游调用时可覆盖：

```json
{
  "temperature": 0.7,
  "max_tokens": 4096,
  "top_p": 1.0
}
```

#### 操作

- 新增 / 编辑 / 删除模型
- **连通性测试**：用配置的上游凭证发送 Ping 请求，验证连接可用
- 启用 / 禁用（禁用后路由到此模型的请求返回 503）
- 查看调用统计（调用量、平均延迟、错误率）

---

### 4.2 接入系统管理

#### 功能概述
管理申请接入 ModelGate 的内部业务系统，记录系统基本信息。

#### 接入系统信息

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| 系统名称 | string | ✅ | 如"智能客服系统" |
| 系统 ID | string | ✅ | 唯一标识，如 `crm-service` |
| 负责人 | string | ✅ | 对接负责人 |
| 联系方式 | string | ❌ | 邮箱或企业微信 |
| 系统描述 | string | ❌ | 系统业务说明 |
| 状态 | enum | ✅ | 启用 / 禁用 |
| 创建时间 | datetime | 自动 | — |

#### 操作

- 新增 / 编辑 / 删除接入系统
- 启用 / 禁用（禁用后该系统所有凭证立即失效）
- 查看系统下的所有凭证和授权

---

### 4.3 授权管理

#### 4.3.1 凭证管理（为接入系统颁发凭证）

每个接入系统可拥有多个凭证（支持多环境：开发/测试/生产），凭证类型可选：

**API Key 模式**

```
系统请求头：
Authorization: Bearer ModelGate-{appId}-{randomKey}

示例：
Authorization: Bearer ModelGate-crm-service-sk_live_abc123xyz
```

**AK/SK 模式**（适合安全要求更高的场景）

```
请求头签名规则（HMAC-SHA256）：
X-Me2ai-AccessKey: {access_key}
X-Me2ai-Timestamp: {unix_timestamp}
X-Me2ai-Nonce: {random_string}
X-Me2ai-Signature: HMAC-SHA256({secret_key}, {access_key}\n{timestamp}\n{nonce}\n{body_hash})
```

凭证字段：

| 字段 | 说明 |
|---|---|
| 凭证名称 | 如"生产环境凭证" |
| 凭证类型 | API Key / AK-SK |
| 凭证值 | API Key 值（或 AK/SK 键值对） |
| 状态开关 | 启用 / 禁用（可随时热切） |
| 过期时间 | 可选，空表示永不过期 |
| 备注 | — |

操作：
- 生成凭证（系统自动随机生成，不可手动填写）
- 查看（Secret Key / API Key 仅在创建时显示一次，之后不再明文展示）
- 禁用 / 启用
- 删除（需二次确认）

#### 4.3.2 模型授权（接入系统可访问哪些模型）

每个接入系统可被授权访问一个或多个模型，并可配置各自的限制：

| 字段 | 说明 |
|---|---|
| 授权模型 | 勾选可访问的模型列表 |
| 授权开关 | 单独的开关，不影响其他模型授权 |
| QPS 限制 | 该系统访问该模型的每秒请求数上限 |
| 日调用量上限 | 超出后当天禁止访问，0 表示不限制 |
| 月 Token 配额 | 按 Token 计量的月度上限，0 表示不限制 |
| 有效期 | 授权有效期，空表示永久 |

---

### 4.4 访问控制

#### 4.4.1 IP 白名单

- 支持精确 IP（`192.168.1.100`）
- 支持 CIDR 网段（`10.0.0.0/8`）
- 支持批量导入
- **作用范围**：可配置在接入系统级别（该系统的所有请求必须来自白名单 IP）
- 白名单为空时表示不限制来源 IP

#### 4.4.2 域名白名单

- 配置允许访问的请求来源域名（校验 `Origin` 或 `Referer` 请求头）
- 支持精确匹配（`app.internal.com`）和通配符（`*.internal.com`）
- 主要用于 Web 前端场景

#### 4.4.3 访问控制开关

| 开关层级 | 说明 |
|---|---|
| **系统级开关** | 关闭后该系统所有请求被拦截，返回 403 |
| **凭证级开关** | 单个凭证的启用/禁用 |
| **模型授权开关** | 该系统对某个模型的访问权限开关 |

优先级：系统开关 > 凭证开关 > 模型授权开关（任一为关则拒绝）

---

### 4.5 流量转发与路由

#### 路由规则

网关根据请求中的 `model` 字段路由到对应上游：

```
请求体：{"model": "gpt-4o", "messages": [...]}
                   │
                   ▼
           查 model_id = "gpt-4o" 的模型配置
                   │
                   ▼
           注入上游鉴权 + 参数转换 + 转发
```

#### 多实例支持（同一模型配置多个上游）

同一个 `model_id` 可配置多个上游实例（如多个 Azure 部署），支持：

| 策略 | 说明 |
|---|---|
| **轮询（Round Robin）** | 均匀分配请求 |
| **权重轮询** | 按配置权重分配 |
| **故障转移** | 主实例失败时自动切换到备用实例 |

#### 流式响应（SSE）

- 上游返回 `text/event-stream` 时，网关直接透传不缓冲
- 下游连接断开时，主动取消上游请求，释放资源

#### 熔断保护

- 错误率 > 50% 触发熔断，30s 后半开探测
- 熔断期间返回 503，避免级联故障

---

### 4.6 API 规范适配

#### 对外统一接口（业务系统调用 ModelGate 使用此格式）

ModelGate 对外**统一暴露 OpenAI 兼容格式**：

```http
POST /v1/chat/completions
Authorization: Bearer {系统凭证}

{
  "model": "gpt-4o",          ← ModelGate 内部的 model_id
  "messages": [...],
  "stream": true,
  "temperature": 0.7
}
```

#### 对上游的参数转换（API 规范类型）

| 规范类型 | 适用提供商 | 说明 |
|---|---|---|
| `openai` | OpenAI、私有化 OpenAI 兼容模型 | 直接透传，无需转换 |
| `azure_openai` | Azure OpenAI | URL 格式不同，Header 中改为 `api-key`，追加 `api-version` |
| `anthropic` | Claude 系列 | 请求格式、角色字段、响应格式均需转换 |
| `custom` | 自定义模型 | 通过配置字段映射规则进行转换 |

#### 自定义参数映射（`custom` 模式）

当上游模型参数格式与 OpenAI 不兼容时，支持配置字段映射：

```json
{
  "request_mapping": {
    "messages":    "inputs",
    "max_tokens":  "max_new_tokens",
    "temperature": "temperature"
  },
  "response_mapping": {
    "choices[0].message.content": "generated_text"
  },
  "extra_params": {
    "do_sample": true
  }
}
```

#### 固定参数注入

可为模型配置固定参数，在转发时强制注入（业务系统无法覆盖）：

```json
{
  "forced_params": {
    "max_tokens": 4096
  }
}
```

---

### 4.7 监控与审计

#### 实时指标（管理后台大盘）

| 指标 | 维度 |
|---|---|
| 请求总量 / 成功率 / 错误率 | 全局 / 按模型 / 按接入系统 |
| 平均响应延迟 / P99 延迟 | 全局 / 按模型 |
| 当前 QPS | 全局 / 按模型 / 按接入系统 |
| Token 消耗量 | 按模型 / 按接入系统 / 按日 |
| 熔断状态 | 按模型上游实例 |

#### 审计日志

每次请求记录如下信息（写入 Kafka，下游可落库或 ELK）：

| 字段 | 说明 |
|---|---|
| trace_id | 链路追踪 ID |
| app_id | 接入系统 ID |
| credential_id | 使用的凭证 ID |
| model_id | 目标模型 ID |
| client_ip | 客户端 IP |
| request_time | 请求时间 |
| latency_ms | 总延迟 |
| prompt_tokens | 输入 Token 数 |
| completion_tokens | 输出 Token 数 |
| status_code | 响应状态码 |
| error_message | 错误信息（如有） |

---

## 5. 管理后台页面清单

| 页面 | 路径 | 功能 |
|---|---|---|
| **首页大盘** | `/dashboard` | 全局调用量、延迟、错误率、Top 模型、Top 系统 |
| **模型列表** | `/models` | 模型列表、新增、启用禁用、连通测试 |
| **模型详情/编辑** | `/models/:id` | 编辑模型信息、鉴权配置、参数配置 |
| **接入系统列表** | `/apps` | 接入系统列表、新增、启用禁用 |
| **接入系统详情** | `/apps/:id` | 系统信息、凭证管理、模型授权、访问控制 |
| **凭证管理** | `/apps/:id/credentials` | 生成/禁用/删除凭证 |
| **模型授权** | `/apps/:id/auth` | 授权模型列表、QPS 配额配置 |
| **访问控制** | `/apps/:id/access` | IP 白名单、域名白名单 |
| **审计日志** | `/audit` | 调用日志查询（按时间/系统/模型筛选） |
| **系统配置** | `/settings` | 网关全局配置（限流默认值、熔断参数等） |

---

## 6. API 接口规范

### 6.1 业务系统调用 ModelGate（对外网关接口）

```
基础路径：/v1
鉴权方式：Authorization: Bearer {api_key}
         或 AK/SK 签名（见 4.3.1）
```

| 接口 | 方法 | 说明 |
|---|---|---|
| `/v1/chat/completions` | POST | 对话补全（支持流式） |
| `/v1/embeddings` | POST | 文本嵌入 |
| `/v1/models` | GET | 查询当前系统可用的模型列表 |

### 6.2 管理后台接口（Admin API）

```
基础路径：/admin
鉴权方式：X-Admin-Token 或 Basic Auth
```

**模型管理**

| 接口 | 方法 | 说明 |
|---|---|---|
| `/admin/models` | GET | 查询模型列表 |
| `/admin/models` | POST | 新增模型 |
| `/admin/models/:id` | GET | 查询模型详情 |
| `/admin/models/:id` | PUT | 更新模型 |
| `/admin/models/:id` | DELETE | 删除模型 |
| `/admin/models/:id/test` | POST | 连通性测试 |
| `/admin/models/:id/toggle` | PUT | 启用/禁用 |

**接入系统管理**

| 接口 | 方法 | 说明 |
|---|---|---|
| `/admin/apps` | GET/POST | 查询/新增接入系统 |
| `/admin/apps/:id` | GET/PUT/DELETE | 详情/更新/删除 |
| `/admin/apps/:id/toggle` | PUT | 启用/禁用系统 |

**凭证管理**

| 接口 | 方法 | 说明 |
|---|---|---|
| `/admin/apps/:id/credentials` | GET/POST | 查询/生成凭证 |
| `/admin/apps/:id/credentials/:cid` | DELETE | 删除凭证 |
| `/admin/apps/:id/credentials/:cid/toggle` | PUT | 启用/禁用凭证 |

**授权管理**

| 接口 | 方法 | 说明 |
|---|---|---|
| `/admin/apps/:id/auth` | GET | 查询模型授权列表 |
| `/admin/apps/:id/auth` | POST | 新增模型授权 |
| `/admin/apps/:id/auth/:modelId` | PUT | 更新授权配置（QPS/配额） |
| `/admin/apps/:id/auth/:modelId/toggle` | PUT | 开关单个模型授权 |
| `/admin/apps/:id/auth/:modelId` | DELETE | 撤销授权 |

**访问控制**

| 接口 | 方法 | 说明 |
|---|---|---|
| `/admin/apps/:id/access` | GET/POST | 查询/新增 IP 或域名规则 |
| `/admin/apps/:id/access/:ruleId` | DELETE | 删除规则 |

---

## 7. 数据库表设计

### 7.1 模型表 `mg_model`

```sql
CREATE TABLE mg_model (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    model_id        VARCHAR(128)    NOT NULL COMMENT '对外暴露的模型标识，如 gpt-4o',
    name            VARCHAR(128)    NOT NULL COMMENT '展示名称',
    provider        VARCHAR(64)     NOT NULL COMMENT 'openai/azure_openai/anthropic/custom',
    model_type      VARCHAR(32)     NOT NULL COMMENT 'chat/embedding/image/audio',
    description     VARCHAR(512)    DEFAULT NULL,
    upstream_url    TEXT            NOT NULL COMMENT '上游请求地址',
    api_spec        VARCHAR(32)     NOT NULL DEFAULT 'openai' COMMENT 'API 规范类型',
    auth_type       VARCHAR(32)     NOT NULL COMMENT 'api_key/ak_sk/azure/none',
    timeout_seconds INT             NOT NULL DEFAULT 300,
    default_params  JSON            DEFAULT NULL COMMENT '模型默认参数',
    forced_params   JSON            DEFAULT NULL COMMENT '强制注入参数（下游不可覆盖）',
    param_mapping   JSON            DEFAULT NULL COMMENT '自定义参数映射（api_spec=custom时）',
    enabled         TINYINT(1)      NOT NULL DEFAULT 1,
    sort_order      INT             NOT NULL DEFAULT 0,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_id (model_id)
);
```

### 7.2 模型凭证表 `mg_model_credential`

```sql
CREATE TABLE mg_model_credential (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    model_id    BIGINT UNSIGNED NOT NULL COMMENT '关联 mg_model.id',
    auth_type   VARCHAR(32)     NOT NULL COMMENT 'api_key/ak_sk/azure/none',
    api_key     TEXT            DEFAULT NULL COMMENT '加密存储',
    access_key  VARCHAR(256)    DEFAULT NULL,
    secret_key  TEXT            DEFAULT NULL COMMENT '加密存储',
    extra_config JSON           DEFAULT NULL COMMENT 'Azure: resource_name/deployment_id/api_version 等',
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_model_id (model_id)
);
```

### 7.3 接入系统表 `mg_app`

```sql
CREATE TABLE mg_app (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    app_id      VARCHAR(128)    NOT NULL COMMENT '系统唯一标识，如 crm-service',
    name        VARCHAR(128)    NOT NULL COMMENT '系统名称',
    owner       VARCHAR(128)    NOT NULL COMMENT '负责人',
    contact     VARCHAR(256)    DEFAULT NULL COMMENT '联系方式',
    description VARCHAR(512)    DEFAULT NULL,
    enabled     TINYINT(1)      NOT NULL DEFAULT 1,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_id (app_id)
);
```

### 7.4 系统凭证表 `mg_app_credential`

```sql
CREATE TABLE mg_app_credential (
    id             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    app_id         BIGINT UNSIGNED NOT NULL COMMENT '关联 mg_app.id',
    name           VARCHAR(128)    NOT NULL COMMENT '凭证名称，如 生产环境',
    cred_type      VARCHAR(32)     NOT NULL COMMENT 'api_key/ak_sk',
    api_key        VARCHAR(512)    DEFAULT NULL COMMENT 'api_key 模式',
    access_key     VARCHAR(256)    DEFAULT NULL COMMENT 'ak_sk 模式，公开',
    secret_key     TEXT            DEFAULT NULL COMMENT 'ak_sk 模式，加密存储，创建后不可查',
    enabled        TINYINT(1)      NOT NULL DEFAULT 1,
    expires_at     DATETIME        DEFAULT NULL COMMENT '过期时间，NULL 表示永不过期',
    remark         VARCHAR(256)    DEFAULT NULL,
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_app_id (app_id),
    UNIQUE KEY uk_api_key (api_key),
    UNIQUE KEY uk_access_key (access_key)
);
```

### 7.5 系统-模型授权表 `mg_app_model_auth`

```sql
CREATE TABLE mg_app_model_auth (
    id              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    app_id          BIGINT UNSIGNED NOT NULL,
    model_id        BIGINT UNSIGNED NOT NULL COMMENT '关联 mg_model.id',
    enabled         TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '单个授权开关',
    qps_limit       INT             NOT NULL DEFAULT 0 COMMENT '每秒请求数，0=不限',
    daily_limit     INT             NOT NULL DEFAULT 0 COMMENT '日调用量上限，0=不限',
    monthly_tokens  BIGINT          NOT NULL DEFAULT 0 COMMENT '月 Token 配额，0=不限',
    expires_at      DATETIME        DEFAULT NULL,
    created_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_app_model (app_id, model_id)
);
```

### 7.6 访问控制表 `mg_access_rule`

```sql
CREATE TABLE mg_access_rule (
    id          BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    app_id      BIGINT UNSIGNED NOT NULL,
    rule_type   VARCHAR(32)     NOT NULL COMMENT 'ip/cidr/domain',
    rule_value  VARCHAR(256)    NOT NULL COMMENT 'IP、CIDR段或域名',
    remark      VARCHAR(256)    DEFAULT NULL,
    created_at  DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_app_id (app_id)
);
```

---

## 8. 非功能性需求

### 8.1 性能

| 指标 | 目标 |
|---|---|
| 鉴权/路由 额外延迟 | < 5ms（本地缓存命中） |
| 单实例吞吐 | ≥ 500 QPS（非流式），≥ 200 并发连接（流式） |
| 缓存刷新 | 授权/白名单变更后 30s 内全部实例生效 |

### 8.2 安全

| 要求 | 说明 |
|---|---|
| 密钥加密存储 | 上游 API Key / Secret Key / AK-SK 使用 AES-256 加密后入库 |
| Secret 仅展示一次 | SK / API Key 创建时明文展示一次，之后不再可查 |
| 常量时间比较 | API Key 比对使用常量时间算法，防时序攻击 |
| AK/SK 防重放 | 签名中携带时间戳，5 分钟窗口外的请求拒绝 |
| 审计覆盖 | 所有管理操作记录操作人、时间、变更内容 |

### 8.3 高可用

- 网关层无状态，水平扩展
- Redis 主从 + Sentinel，或 Redis Cluster
- 本地内存缓存兜底，Redis 故障时降级放行（已认证过的凭证）
- 上游故障熔断，自动摘除故障实例

### 8.4 可扩展性

- 新增模型提供商只需实现 `ProviderAdapter` 接口
- 新增参数转换规则通过配置完成，无需改代码

---

## 9. 待确认事项

| 编号 | 问题 | 影响模块 |
|---|---|---|
| Q1 | AK/SK 签名规范是否有内部统一标准？还是参考 AWS 签名 V4？ | 授权管理 |
| Q2 | 上游密钥加密：用内置对称密钥还是对接公司密钥管理服务（KMS）？ | 安全 |
| Q3 | 管理后台是否需要对接公司内部 SSO / LDAP 鉴权？ | 管理后台 |
| Q4 | Token 计量是否需要实时扣减配额，还是事后统计超限告警即可？ | 授权管理 |
| Q5 | 多实例负载均衡（轮询/权重/故障转移）是否是 v1 必须功能？ | 路由 |
| Q6 | 审计日志保留周期？是否需要对接现有的日志平台（ELK/Loki）？ | 审计 |
| Q7 | 管理后台 UI 框架是否有内部规范（React/Vue/内部组件库）？ | 前端 |
