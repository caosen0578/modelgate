# modelgate 数据库部署入口（2026-09-22）

## 归属

仅mg_*模型网关表。ModelGate管理接口通过独立SLB入口访问，不因此加入应用网关微服务或权限库。

表清单（12）：`mg_model`、`mg_model_credential`、`mg_app`、`mg_app_credential`、`mg_app_model_auth`、`mg_access_rule`、`mg_gateway_config`、`mg_request_payload`、`mg_audit_log`、`mg_service`、`mg_service_credential`、`mg_app_service_auth`。

## 首次部署：全量

1. SQL客户端显式选中本项目新申请的空库，执行 `../../sql/modelgate_schema.sql`。
2. 脚本内仅有mg_gateway_config默认配置，不含真实应用密钥、接入账号。既有完整表结构已包含历史升级，不要全量后再跑所有upgrade。
3. 核对结构与索引、配置最小权限账号，先验收再接入流量。

## 已有库：增量

manifest.json的upgradeCandidates只是候选清单，不是顺序执行队列。逐个阅读脚本前置条件、比较SHOW CREATE TABLE并备份后选择。修复数据、密文格式转换、字段重命名等脚本必须单独审核，不得按文件名全部执行。
本目录的manifest.json给出本项目唯一执行清单与原始路径，SQL原文件保留在其权威目录，不重复复制多套“全量”。没有自动迁移/自动执行器。申请后的正式库名尚未给出，不预填数据库名或凭据。

## 代码与数据源

tdsqlDataSource只连接模型库；配置键为datasource.local.*（当前ModelGate所有环境使用Druid直连）。不要机械套用user项目的Venus profile配置。Mapper与实体都在modelgate.server命名空间。
三个新库使用不同读写账号；行为采集另申请模型库指定两表的SELECT权限，无模型库DDL/DML权限。公共库不默认并入这三个库。
实际配置由各环境下发；本轮未修改带连接凭据的application-local.yml，也未改现有数据库连接目标。不得仅换库名就宣称完成上线：独立库需搬迁本库历史数据和验收。

## 检查与发布

在项目根目录执行 `node deploy/database/check.mjs`：校验全量表清单、脚本写入表归属、实体表归属、行为外部只读Mapper。是静态护栏，不是完整SQL解析或MySQL执行验收。
先备份/停写，再结构部署与数据迁移，核对行数、主键、自增、唯一键、索引、字符集、逻辑删除与关联完整性。保持sys_source业务隔离，不全库改来源。

两个曾混合的user/行为应用须clean构建后重启，避免旧target/classes继续加载已归档Mapper。测试仅静态/单元时不能声称已经独立库验收。真实数据库不由本次整理自动创建、迁移、授权或删除。
