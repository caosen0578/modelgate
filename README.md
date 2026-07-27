# ModelGate

企业内网 AI 模型流量网关：统一注册和管理多个 AI 模型（自建/外部 API），为内部系统颁发凭证并控制模型访问范围，对外暴露 **OpenAI 兼容接口**（`/v1/chat/completions`），将请求鉴权、限流、路由并转发至对应上游。

## 技术栈

| 层次 | 技术 | 版本 |
|---|---|---|
| 语言 / 框架 | Java / Spring Boot | 17 / 2.7.18 |
| 网关 | Spring Cloud Gateway（WebFlux 响应式） | Spring Cloud 2021.0.9 |
| 持久化 | MyBatis Plus（Lambda API，无 XML）+ MySQL 8（生产 TDSQL + Druid） | 3.5.9 |
| 缓存 | Caffeine L1 + 内网 Redis L2（`com.pab.framework:redis`） | — |
| 熔断 | Resilience4j | 1.7.0 |
| 配置中心 | Apollo（生产全部配置热更新） | 2.3.0 |
| 监控 | Actuator + Micrometer Prometheus | — |
| API 文档 | springdoc-openapi（WebFlux UI） | 1.7.0 |

> 无 Kafka、无 Consul（审计日志仅写 MySQL；服务注册未接入）。外部资源仅需 **MySQL(TDSQL) / Redis / Apollo** 三项。

## 快速开始

```bash
# 1. 初始化数据库（全新环境）
mysql -u root -p < sql/V1__baseline_schema.sql
#    存量环境升级：sql/V2__upgrade_base_fields.sql

# 2. 必需环境变量
export MODELGATE_CRYPTO_KEY=$(openssl rand -base64 32)   # 凭证加密密钥（32字节 base64）
export ADMIN_TOKEN_OPS=<替换默认管理token>
export ADMIN_USERNAME=<管理页Basic用户> ADMIN_PASSWORD=<密码>

# 3. 构建与启动
mvn clean package
java -jar target/modelgate-1.0.0-SNAPSHOT.jar
```

- 管理后台：`http://localhost:8080/admin.html`（静态页，全功能 CRUD + 调用预览）
- Swagger：`/swagger-ui.html`（生产建议关闭或纳入 Admin 鉴权）
- 健康检查：`/actuator/health`；指标：`/actuator/prometheus`

## 本地联调（local Profile，脱离 Apollo/Redis 集群/内网 SDK）

> 用于本地起服务、通过 `admin.html` 做增删改联调。只依赖一个本地 MySQL；Apollo/Redis 集群/Venus 数据源全部由本地兼容实现替代（`config/LocalInfraConfig` + `com/pab/**` 源码桩）。**这些兼容件合入内网时忽略即可。**

```bash
# 0. 用 JDK 17 构建/运行（注意：本机默认 java 可能是 8，需显式指定 17）
export JAVA_HOME=/path/to/jdk-17

# 1. 起本地 MySQL 并建库（二选一）
mysql -uroot -p -e "SOURCE sql/V1__baseline_schema.sql"          # 已有 MySQL
# docker run --name mg-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=modelgate \
#   -p 3306:3306 -d mysql:8 && mysql -uroot -proot modelgate < sql/V1__baseline_schema.sql   # 或用 Docker

# 2. 改 src/main/resources/application-local.yml 里的 spring.datasource（url/username/password）

# 3. 构建并以 local Profile 启动
mvn clean package -DskipTests
java -jar target/modelgate-1.0.0-SNAPSHOT.jar --spring.profiles.active=local

# 4. 浏览器打开 http://localhost:8080/admin.html
#    顶部 "ADMIN TOKEN" 输入框填：local-admin-token（application-local.yml 中配置）
#    之后即可增删改（接口按浏览器地址自动取后端，无需改 admin.html）
```

local Profile 默认值（见 `application-local.yml`）：Admin Token = `local-admin-token`，Basic Auth 关闭，加密密钥内置测试值，IP 白名单含 `127.0.0.1`/`::1`。

## 业务调用（OpenAI SDK 兼容）

```
POST /v1/chat/completions
Authorization: Bearer {api_key}          # 或 AKSK {ak}:{timestamp}:{signature}

{"model": "{model_id}", "messages": [...], "stream": true}
```

内部系统仅需修改 `base_url` 与 `api_key`，无需其他代码改动。

## 文档索引

| 文档 | 内容 |
|---|---|
| [spec/modelgate/00-文档索引.md](spec/modelgate/00-文档索引.md) | 全部文档导航 + 架构总览 |
| 01-需求规约 / 02-架构设计 / 03-编码规范 | 约束文档（开发必读） |
| 04-接口与数据结构 | 建表 SQL、管理接口契约、Entity/BO/VO 定义 |
| 05-边界限制与禁止规则 / 06-验收标准 | 红线与验收清单 |
| 09-压测方案 | JMeter 压测场景、JVM/连接池参数、指标与红线 |
| 10-部署与运维手册 | 资源清单、环境变量、监控告警、上线检查清单 |
| [sql/](sql/) | V1 基线建表 + V2 存量升级脚本 |

## 目录结构

```
src/main/java/com/pab/ficc/
├── cus/                    行内基础包源码（Result/BizException，后期替换为行内 jar）
└── ibp/modelgate/server/
    ├── filter/             网关过滤器链（鉴权→访问控制→授权→配额→路由→熔断）
    ├── adapter/            上游协议适配（OpenAI/Azure/Anthropic/Custom）
    ├── controller/         管理后台 REST（路径常量统一 UrlConstant）
    ├── domain/             entity(*Entity) / bo(*RequestBO) / vo(*VO)
    ├── mapper/ service/    MyBatis Plus + 业务逻辑
    ├── admin/ crypto/ cache/ config/ common/ metrics/ apollo/
    └── ModelGateApplication.java
```
