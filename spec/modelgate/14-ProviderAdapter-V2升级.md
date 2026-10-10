# ProviderAdapter V2（2026-10-07）

网关客户端响应模式按模型配置选择 `PASSTHROUGH`（原生响应）或 `OPENAI_COMPATIBLE`。
缺省、存量数据库行和旧客户端缺省字段一律使用原生响应。客户端请求仍是现有 `/v1/**` 的
POST JSON 契约，顶层 `model` 指向网关别名；响应模式不代表接受供应商原生请求。

## 实现顺序与契约

1. 用 `LegacyAdapterContractTest` 锁定四个适配器的正常请求、鉴权和路径行为。
2. `ProviderAdapterRegistry` 启动时拒绝重复/空 ID，统一管理能力目录和配置校验。
3. `ProviderRequestPipeline` 供真实业务、草稿探测、已保存探测和管理对话测试共用。
   参数/路由身份处理 → 请求 codec/URI/Header → 异步 AuthProvider → PreparedUpstreamRequest。
4. 保留旧三个请求方法的 deprecated bridge，现有四协议迁移后仍可继续接入旧实现。
5. `ResponseSession` 是每响应独立的状态；`ResponseInspector` 提取 usage/错误，
   `ProviderResponseDecorator` 把客户端实际收到的字节交给现有 PayloadCaptureFilter。
6. Anthropic 增加 JSON/SSE → OpenAI Chat Completions 转换，并覆盖 tool use/result、
   图像输入、多个 system/developer 消息、工具增量参数、usage、结束原因和上游错误。
7. 两套管理页面都消费能力目录；旧后端缺少目录接口时退回四协议、仅原生响应的表单。

`ProviderModelConfig` / `ProviderCredential` 为只读快照，不把 Entity 带进新协议接口。
PreparedUpstreamRequest 含最终 method/URI/headers/body；密钥视图和请求结果的 toString 均脱敏。
AuthProvider 在最终 URL、Host、请求体及 Content-Length 确定后执行，可异步实现 OAuth/STS/签名。
当前仍只提供四协议原有静态 Header 鉴权，没有声称实现 AWS SigV4 或 OAuth 刷新。

`ProviderRequestBody` 支持可重新订阅的 Buffered/Streaming 两种 body。现有模型入口只生成
Buffered JSON；multipart、流式上传、Realtime WebSocket 需另行升级 BodyParse/路由入口。
创建/更新/运行时配置校验使用同一目录。存在强制参数的畸形 body、未知协议、未知响应模式、
不兼容模型类型/operation/authType 均明确拒绝。Azure/Anthropic 必须转换时不再静默发送原 body。
模型无参数策略且不需要身份改写时仍返回原 byte[]；已知 model 偏移的身份改写仍使用字节替换。

## 当前响应能力

| apiSpec | 原生响应 | OpenAI 兼容响应 | 备注 |
|---|---|---|---|
| openai | 支持 | 支持（本身即兼容格式） | JSON/SSE usage 旁路解析，二进制原样透传 |
| azure_openai | 支持 | 支持（本身即兼容格式） | 完整部署 URL，配置的 query 优先于客户端同名 query |
| anthropic | 支持 | 支持 Chat Completions JSON/SSE | 未知内容块/事件在 provider_content/provider_event 扩展保留 |
| custom | 支持 | 不提供通用转换 | 自定义映射只处理请求，无法推断任意上游响应结构 |

原生模式保持原始字节与 flush 分组。协议检查在有界 CPU 队列上执行，prefetch=1；
JSON 旁路检查最多 8 MiB，超出只停止检查；SSE 半帧最多 1 MiB。
标准化 JSON 聚合上限 8 MiB、SSE 单帧上限 1 MiB；达到上限明确报协议错误。
流式转换不聚合整条 SSE，CR/LF/CRLF、多行 data、UTF-8 跨块、心跳、取消/错误及 buffer 释放均有回归。
响应改写移除失效 Content-Length/Content-Encoding/ETag/Digest，按真实 content-type 解码；
上游忽略 Accept-Encoding: identity 时，原生可透传压缩响应，标准化拒绝未实现的压缩解码。
已提交响应后的协议错误以终止流体现，无法再更改 HTTP status。

NDJSON、AWS EventStream、Gemini、Cohere、Bedrock、Vertex 尚未增加到能力目录；
必须在对应 codec、认证和契约用例完成后逐个注册，不能仅靠页面新增选项宣称支持。
当前 adapterConfig 为扩展保留，只允许空 JSON 对象；不要把秘密放入明文 adapterConfig/extraConfig。
新增需要 service-account/session-token 的协议时，另行添加加密凭证配置及其字段 schema。

## 管理接口与双前端

新增 `GET /modelgate/models/provider-adapters`，返回标准管理 Result，data 为 ProviderAdapterVO 数组：
adapterId、displayName、configVersion、modelTypes、operations、streamFormats、contentTypes、authSchemes、
responseModes、features、defaultPaths、pathEditable、parameterMapping。不返回任何凭证值/可执行 HTML。
外部接口鉴权层需要将该路径登记为 L1 只读查询，建议与模型详情查询使用相同权限。

模型 Create/Update/Test BO 和 ModelVO 新增 `responseMode`、`configVersion`、`adapterConfig`。
更新缺省 responseMode 时保留数据库原值，不通过旧页面把已开启的标准化响应重置成原生。
两套页面：`D:\code\modelgate\src\main\resources\static\admin.html` 与
`D:\code\static-framework\src\modules\modelgate`。厂商展示标签与 apiSpec 独立。
Custom AK/SK 的实际行为是发送 X-Access-Key/X-Secret-Key，不是 HMAC，页面已修正文案。
timeoutMs 只为管理测试提供参考，没有在业务转发或 ProviderAdapter 中添加超时/重试。

## 数据库发布

全新库使用 `sql/modelgate_schema.sql`。已有库先人工检查并执行一次
`sql/upgrade_20261007_provider_adapter_v2.sql`，新增 response_mode/config_version/adapter_config、
扩大 api_spec 并修正 timeout_ms 注释。脚本登记于 deploy/database/manifest.json；不自动执行 DDL。
然后部署后端，再部署两套页面；检查新只读接口权限，再按需为指定模型开启 OPENAI_COMPATIBLE。
部署前检查历史 apiSpec/modelType/authType 组合：此前页面允许录入的无效组合现在会明确拒绝，
应按目录声明的能力修正；未知协议和畸形参数配置不再悄悄透传。
回退旧后端前先把已启用标准化响应的模型切回原生，避免客户端响应格式发生意外变化；新增列无需删除。

## 验证

`mvn test` 现在运行 JUnit 5 的协议契约/流式/过滤器测试，并通过 LegacyRegressionSuiteTest
逐个启动历史 main 用例，不再出现 main 用例被发现但实际 0 tests 的假通过。
另运行数据库 inventory、admin JS 语法、static-framework 类型检查、定向 ESLint 与构建。
接口/流式测试使用本地 mock，不读取生产凭证，不请求真实供应商，不执行数据库迁移。
Anthropic event 格式依据 https://platform.claude.com/docs/en/build-with-claude/streaming 核对。
