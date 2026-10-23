# 版本发布检查清单

更新时间：2026-10-23

本页只保留可复用流程。当前路线见[开发计划](development-plan.md)，每版的修复、验证、上传和发布状态只记录在对应发布说明（当前：[1.10.1](release-notes-1.10.1.md)）。历史记录不随新版本重写。

## 本地验证

1. 11 个 POM（父工程 + 10 个子模块）的项目/父版本一致，内部依赖使用 `${project.version}`。保留当前主线的 JDK 与 Boot 基线。
2. 使用最低支持 JDK 执行 `mvn -B -ntp clean verify`，汇总 Surefire XML 的 tests/failures/errors/skipped。Redis、Redis Cluster、NATS/JetStream、mesh、Starter 装配和多节点 E2E 都须运行，不能把环境不满足的跳过写成全量通过。
3. 生成 SBOM：`mvn -B -ntp -Psbom -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom`。检查 `target/netty-spring-sbom.json` / `.xml` 的版本和依赖。
4. Demo fat jar 包装成功；默认 classpath（不额外加 jnats）下验证单机健康检查与 WebSocket roundtrip，并执行 `docker-demo/README.md` 的双节点广播 smoke，确认运行目标版本。用独立 Compose 项目名隔离验证环境，仅清理本次创建的容器/网络。
5. 对发布差异独立审查，覆盖 optional 依赖缺席、资源清理、默认配置与消息格式兼容。minor 另需按功能正确性、资源、安全、API 契约四视角并行审查并做对抗复核；发布说明记录发现与处置。
6. 验证 release profile 的 sources、Javadoc、GPG 签名产物；跳过 GPG 的构建不能算已签名。demo 不发布到 Central。

## 发布动作与远端门槛

1. 审核差异与发布说明，创建并推送发布候选提交。提交包含验证结果，不带入本地缓存和实验文件。
2. 对候选提交手动触发 `CI` workflow（包含 Dependency Scan）和 `Docker Demo Smoke`（其 push/PR 有路径过滤），确认 `Maven Test`、`Generate SBOM`、扫描和 Docker smoke 的实际结果。发布条件满足后，在该提交创建并推送 annotated tag `v<version>`；再次确认 tag 对应 CI，避免先占用正式标签再发现阻断问题。
3. **当前 CI 对所有 `v*` tag 都运行 Dependency Scan，优先使用 `NVD_API_KEY`；未配置时使用 Dependency-Check 官方 NVD 镜像。** 镜像的 `cache.properties` 时间戳必须在过去 72 小时内（最多允许超前 5 分钟），不可读取、过期或异常时间均阻断门禁；保留 CVSS 7.0 与扫描错误即失败的要求。按[依赖治理](dependency-governance.md)完成扫描与告警 triage，记录实际数据源和镜像时间戳，不能把红色 CI 写成发布验证通过。企业安全能力的额外承诺按该版范围验收。
4. 使用已配置凭据的发布环境上传：

   ```sh
   mvn -B -ntp -P release -pl '!demo-netty-web-spring-boot-starter' -DskipTests deploy
   ```

   仅在同一源码完成全量测试后跳过重复测试。Windows 按环境指定 GPG 可执行文件。**必须使用 `-P release`**，由此生成 sources/Javadoc/GPG 和 Central bundle；普通 `mvn deploy` 不等价。
5. 当前 `autoPublish=false` / `waitUntil=validated`：上传完成只代表 Portal 验证通过。记录 deployment ID，完成 Portal 发布并核实全部 10 个制品（父 POM + 9 个库模块）可下载后，才标为已发布。

## 文档同步矩阵

| 文件 | 维护内容 | 更新时机 |
|---|---|---|
| 11 个 `pom.xml` | 构建版本 | 切版 |
| `docs/release-notes-<version>.md` | 本版变更、兼容性、逐项验证、CI、tag、Central 状态 | 每个发布步骤完成时 |
| `docs/development-plan.md` | 当前公开版本、本地收尾版本、下一主线与执行进度 | 状态改变时，不复制测试流水账 |
| `README.md` | EN/中文版本摘要、依赖示例、公开版本列表与发布说明链接 | 准备时明确 pending；Central 可下载后才更新公开版本 |
| `docs/api-guide.md` | 源码版本、API/配置变化、可用依赖示例 | API 变化及发布时 |
| 教程、FAQ、SO 草稿 | 发布门槛链接、引用版本与实际能力 | 引用版本发布后核对；外部发表单独处理 |
| 配置文档与 `additional-spring-configuration-metadata.json` | 新配置默认值、说明、IDE 补全 | 配置变化时 |
| `.claude/CLAUDE.md` | 指向统一状态与路线，保留工程约定 | 约定变化时，不复制版本史 |

检查旧版本号时区分历史记录、`since` 标记和当前示例，避免全仓替换历史：

```sh
rg -n 'OLD|Boot 3|2\.0\.0' README.md docs/api-guide.md docs/development-plan.md
rg -n '<version>OLD</version>' -g pom.xml
git diff --check
```

新增文档优先引用现有状态源；开发实施步骤放开发计划，发布操作放本清单，架构原理放设计文档，发布事实放发布说明。
