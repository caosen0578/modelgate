# ModelGate

企业内网 AI 模型流量网关：统一注册和管理多个 AI 模型（自建/外部 API），为内部系统颁发凭证并控制模型访问范围，对外暴露 **OpenAI 兼容接口**（`/v1/chat/completions`），将请求鉴权、限流、路由并转发至对应上游。

## 技术栈

| 层次 | 技术 | 版本 |
|---|---|---|
| 语言 / 框架 | Java / Spring Boot | 17 / 3.5.6 |
| 网关 | Spring Cloud Gateway（WebFlux 响应式，坐标 `spring-cloud-starter-gateway-server-webflux`） | Spring Cloud 2025.0.0 |
| 持久化 | MyBatis Plus（Lambda API，无 XML）+ MySQL 8（生产 TDSQL + Druid） | 3.5.7 (spring-boot3 starter) |
| 缓存 / 配额 | Caffeine L1（配置读取，miss 回源 MySQL）+ Redis（INCR/INCRBY/EXPIRE 原生命令计数，**内网禁用 Lua**；不是 L2） | — |
| 熔断 | Resilience4j | 2.2.0 |
| 配置 | 配置文件（apollo-client 已移除） | — |
| 监控 / 探针 | Actuator health/info + 行内容器 detection 三接口；**不接 Prometheus/Grafana**，可观测性走日志 | — |
| API 文档 | 无（内网无 webflux 版 springdoc）；契约见 spec/04 | — |

> 无 Kafka、无 Consul（审计日志仅写 MySQL；服务注册未接入）。外部资源仅需 **MySQL(TDSQL) / Redis** 两项（Apollo 依赖已移除）。

## 快速开始

```bash
# 1. 初始化数据库（在目标库下执行完整建表脚本）
mysql -u root -p 你的库名 < sql/modelgate_schema.sql

# 2. 编辑 src/main/resources/application.yml，把下面几项改成目标环境的真实值
#    （不用环境变量，不用 Spring Profile；这是唯一配置文件，改字面量即可生效）：
#      datasource.local.{url,username,password}   —— 目标数据库
#      redis.local.{host,port,password}            —— 目标 Redis
#      modelgate.crypto.key                        —— 生产专用密钥（64 位十六进制字符 = 32 字节 AES-256）
#        生成方法（openssl/node/PowerShell/jshell 四选一）见 spec/modelgate/10 §2.1；
#        生产必须与数据库连接串分离保管，见 spec/modelgate/10 §2.2；
#        ⚠️ 生成一次存好，勿每次部署重新生成——换密钥则库中全部存量密文解不开；
#        ⚠️ 仓库默认值是本地联调测试密钥，部署前必须替换，否则等于用公开密钥加密全部凭证。
# 注：ADMIN_TOKEN_OPS / ADMIN_USERNAME / ADMIN_PASSWORD 已随 AdminAuthFilter 移除，
#     管理面 /modelgate/** 的鉴权由内网体系承担，本进程不再校验。

# 3. 构建与启动
mvn clean package
java -jar target/modelgate-1.0.0-SNAPSHOT.jar
```

- 管理后台：`http://localhost:10811/admin.html`（静态页，全功能 CRUD + 调用预览）
- 行内容器探针：`/actuator/detection/running`、`/actuator/detection/available`、
  `/actuator/detection/shutdown`（均按平台约定返回 200；状态语义见 spec/10）；标准健康页仍为 `/actuator/health`。
  **无指标端点**，告警走日志关键字，见 spec/10 §5

> 无在线 Swagger UI：内网仓库无 webflux 版 springdoc，且 webmvc 版会带入 spring-webmvc
> 导致 Spring Cloud Gateway 拒绝启动。接口契约见 [04-接口与数据结构](spec/modelgate/04-接口与数据结构.md)。

## 本地联调（用自己的本地 MySQL 替换默认联调库）

> `application.yml` 是唯一配置文件、单一固定文档，**没有 Spring Profile、没有环境变量间接层**
> （2026-08-18 起代码与文档已完全对齐这条规矩，此前描述的 `@Profile("local")` 兼容 Bean 与
> `spring.config.activate.on-profile: local` 文档块均已随 §19 去 Profile 化改造移除）。
> 仓库自带的 `datasource.local.*` 指向一个共享联调库，直接能跑；想换成自己本地的 MySQL 时按下面操作。

```bash
# 0. 用 JDK 17 构建/运行（注意：本机默认 java 可能是 8，需显式指定 17）
export JAVA_HOME=/path/to/jdk-17

# 1. 起本地 MySQL 并建库
mysql -uroot -p 你的库名 < sql/modelgate_schema.sql          # 已有 MySQL
# docker run --name mg-mysql -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=modelgate \
#   -p 3306:3306 -d mysql:8 && mysql -uroot -proot modelgate < sql/modelgate_schema.sql   # 或用 Docker

# 2. 直接改 application.yml 里的 datasource.local.{url,username,password}，改完即生效，
#    不用碰任何环境变量、不用切 Profile

# 3. 构建并启动
mvn clean package -DskipTests
java -jar target/modelgate-1.0.0-SNAPSHOT.jar

# 4. 浏览器打开 http://localhost:10811/admin.html
#    页面无需填任何令牌：AdminAuthFilter 已移除，管理面鉴权由内网体系承担
```

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
| [sql/](sql/) | 完整建表脚本 `modelgate_schema.sql`（8 张表，字段注释齐全） |

## 目录结构

```
src/main/java/com/pab/ficc/
└── ibp/modelgate/server/          （Result/BizException/BaseBo 由行内 ibp-web、ibp-mybatis jar 提供）
    ├── filter/             网关过滤器链（鉴权→访问控制→授权→配额→路由→熔断）
    ├── adapter/            上游协议适配（OpenAI/Azure/Anthropic/Custom）
    ├── controller/         管理后台 REST（路径常量统一 UrlConstant）
    ├── domain/             entity(*Entity) / bo(*RequestBO) / vo(*VO)
    ├── mapper/ service/    MyBatis Plus + 业务逻辑
    ├── admin/ crypto/ cache/ config/ common/
    └── ModelGateApplication.java
```
