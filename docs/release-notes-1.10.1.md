# Release Notes — 1.10.1

更新时间：2026-10-23

## 发布状态（本版唯一记录）

**本地构建、回归、冒烟与签名验证完成；尚未发布到 Maven Central。** 版本号已统一为 `1.10.1`；验证结果见下表。上传、Central 发布、Git tag/远端 CI 均需分别记录，构建通过不代表已经发布。

教程、FAQ、SO 草稿中使用 `1.10.1` 的依赖示例，须在 Central 可下载该版本后再对外发布。当前公开版本与下一步路线见[开发计划](development-plan.md)。

## 修复与兼容性

- 修复集群 starter 在没有可选 `jnats` 依赖的默认 Redis 部署中启动失败（`NoClassDefFoundError: io/nats/client/...`）。将带 NATS 类型的 bean 方法隔离到类级条件配置中；NATS 显式启用却缺依赖时仍快速报错。修复提交：`15a25d7`，附三项回归测试。
- 弃用从未有注入处理器的 `@AutowiredMessageSender`，保留注解以维持源码兼容。业务应使用构造器注入 `MessageSender`；同步修正文档中的握手参数说明。修复提交：`69dfc95`。
- Docker demo 从构建目录存在的版本化 jar 中选最新构建，避免版本升级后运行旧 jar。
- 修正纯装配测试的空闲端口探测竞争：直接绑定端口 `0`，由操作系统分配；保留负端口失败与启动状态断言。
- 修正可选依赖测试的前置条件提示：无可用 Redis 时不会提前调用 `uri()` 导致异常，按原有约定跳过；正式发布验证必须报告跳过数。

本版继续使用 **Spring Boot 2.7.18 / JDK 17 / Netty 4.1.134.Final**。配置键、消息线格式与投递语义保持兼容；未增加 Boot 3/4 支持。Boot 4.1 迁移属于 `2.0.0`，步骤统一在[开发计划](development-plan.md)。上游 2.7 OSS 支持已结束，本版不是上游依赖的全面安全修复，详见[依赖治理](dependency-governance.md)。

## 验证记录

| 检查 | 结果 |
|---|---|
| 11 个 POM 版本一致 | 已统一为 1.10.1 |
| JDK 17 全 reactor verify | 650 tests / 0 failures / 0 errors / 0 skipped，11 个模块 SUCCESS；Redis、Redis Cluster、NATS/JetStream 与 mesh 测试已执行 |
| CycloneDX SBOM | JSON/XML 已生成；根组件版本 1.10.1，53 个依赖组件 |
| 默认无 jnats 的双节点 Docker 广播 | PASS；alice 在 node-a、bob 在 node-b，收到带正确 originNode 的广播；健康 UP，容器进程明确运行 1.10.1 jar |
| 单机 demo | 健康 UP，`/ws/test` 真实 WebSocket 回显 PASS |
| 发布制品 sources / Javadoc / GPG | release profile 本地 verify SUCCESS，10 个发布模块的 34 份分离签名逐一验证通过，未上传 |
| Dependency Scan / 漏洞 triage | 本地未运行（未配置 NVD_API_KEY）；发布 tag 的远端扫描与处置待完成 |
| Git 提交、v1.10.1 tag、远端 CI | 待执行 |
| Central 上传及发布 | 待执行 |

环境：GraalVM JDK 17.0.11、Maven 3.9.16、Docker Desktop；2026-10-23 执行。首轮 clean 构建暴露装配测试的端口竞争，修复后重新执行完整 `verify` 通过。项目 demo fat jar 的依赖清单确认没有 jnats。Docker 验证使用独立 Compose 项目 `netty-spring-1101-check` 和本机 18080/18081 端口，结束后已清理本次容器及网络。

验证命令（使用 JDK 17；新环境先 clean）：

```sh
mvn -B -ntp clean verify -Psbom org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom
mvn -B -ntp -P release -pl '!demo-netty-web-spring-boot-starter' -DskipTests verify
docker compose -f docker-demo/docker-compose.yml up -d --build
# docker-demo/ 下安装依赖后运行 node smoke.js
```

## 升级与发布

Central 发布后，将所用 `io.github.berrywang1996:netty-*` 依赖统一升级到 `1.10.1`。从 `1.10.0` 升级无需配置迁移；使用 `@AutowiredMessageSender` 的代码须改为构造注入，旧注解此前也不会自动注入字段。

发布流程复用[发布检查清单](release-checklist.md)。使用 `-P release` 排除 demo；该 profile 的 `autoPublish=false`，上传验证完成后仍须在 Central Portal 发布并核实可下载，再更新本页状态与 README 的公开版本。不要将待发布版本写入“已发布”列表。
