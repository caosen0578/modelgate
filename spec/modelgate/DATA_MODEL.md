# ModelGate · 数据模型速查

> 完整建表 SQL 见 PRD.md 第 7 节，本文档提供 ER 图和字段枚举值速查

---

## ER 关系图

```
mg_model ─────────────────── mg_model_credential
   │ 1                                       1
   │
   │ N (通过 model_id 路由)
   │
mg_app_model_auth ──────────── mg_app
   N                                  │ 1
                                      │
                            ┌─────────┴──────────┐
                            │                    │
                    mg_app_credential   mg_access_rule
                       (凭证: N)              (访问规则: N)
```

---

## 枚举值定义

### 模型提供商 `provider`

| 值 | 含义 |
|---|---|
| `openai` | OpenAI |
| `azure_openai` | Azure OpenAI |
| `anthropic` | Anthropic Claude |
| `private` | 私有化部署（OpenAI 兼容） |
| `custom` | 自定义上游 |

### 模型类型 `model_type`

| 值 | 含义 |
|---|---|
| `chat` | 对话补全 |
| `embedding` | 文本嵌入 |
| `image` | 图像生成 |
| `audio` | 语音 |

### API 规范 `api_spec`

| 值 | 参数转换行为 |
|---|---|
| `openai` | 直接透传，不转换 |
| `azure_openai` | URL/Header 适配 |
| `anthropic` | 请求体 + 响应体全量转换 |
| `custom` | 按 `param_mapping` 字段转换 |

### 上游鉴权类型 `auth_type`

| 值 | 说明 |
|---|---|
| `api_key` | `Authorization: Bearer {key}` |
| `ak_sk` | HMAC 签名 |
| `azure` | `api-key` Header + URL 参数 |
| `none` | 不鉴权（内网私有模型） |

### 系统凭证类型 `cred_type`

| 值 | 说明 |
|---|---|
| `api_key` | 单一密钥，请求头 Bearer |
| `ak_sk` | AK 公开 + SK 签名 |

### 访问规则类型 `rule_type`

| 值 | 示例 |
|---|---|
| `ip` | `192.168.1.100` |
| `cidr` | `10.0.0.0/8` |
| `domain` | `app.internal.com` |
| `domain_wildcard` | `*.internal.com` |

---

## 关键业务规则

1. **App 禁用** → 该 App 下所有凭证立即失效（缓存强制刷新）
2. **凭证禁用** → 仅该凭证失效，其他凭证不受影响
3. **模型授权禁用** → 该 App 访问该模型返回 403，其他模型不受影响
4. **模型禁用** → 所有 App 访问该模型返回 503
5. **授权过期** → 过期后等同于禁用，返回 403，不自动删除记录
6. **访问规则为空** → 不限制来源 IP/域名
