# 依赖治理与供应链门禁

更新时间：2026-10-23

## 目标

- 让后续企业安全发布前具备可重复执行的依赖清单和漏洞扫描入口。
- 把 GitHub Dependabot 或等效工具提示的问题纳入发布判断，而不是只依赖人工记忆。
- 普通开发命令保持轻量，依赖扫描和 SBOM 生成只在显式 profile 下运行。
- 本地普通构建不自动扫描；当前 CI 对所有 `v*` tag 执行 Dependency Scan，功能补丁也不能宣称跳过该 job 后“全部 CI 通过”。扫描结果与尚未完成项记录到对应发布说明。

## 版本一致性

- 根 POM 显式导入 `io.netty:netty-bom:${netty.version}`，避免 Netty 子模块被 Spring Boot BOM 中较旧的版本覆盖，导致 Netty 组件混版。
- Runtime 不再依赖 `netty-all`，只显式引入当前代码实际使用的 `netty-codec-http`、`netty-handler`、`netty-transport`、`netty-buffer` 和 `netty-common`，减少无关协议模块带来的漏洞面和扫描噪音。
- Spring Boot 仍由 `org.springframework.boot:spring-boot-dependencies:${spring-boot.version}` 管理；如果后续升级 Spring Boot，需要重新确认 Netty BOM 覆盖顺序和 `mvn dependency:tree -Dincludes=io.netty` 输出。
- **logback 1.2.13 显式 pin（自 `1.7.1` 起）**：Spring Boot 2.7.18 BOM 默认 logback 1.2.12 仍有 CVE-2023-6378（`SocketReceiver` / `SocketAppender` 序列化漏洞）。根 POM 的 `<dependencyManagement>` 在 `spring-boot-dependencies` 之前显式声明 `logback-classic:1.2.13` 与 `logback-core:1.2.13` 覆盖。
- **spring-framework 5.3.39 显式 pin（自 `1.8.0` 起）**：根 POM 在 `spring-boot-dependencies` 之前导入 `org.springframework:spring-framework-bom:5.3.39`，覆盖 Boot 2.7.18 管理的版本。该覆盖不代表消除了所有 Spring CVE；每个漏洞需按官方影响与修复版本重新核实。
- **snakeyaml 1.33 显式 pin（自 `1.8.0` 起）**：Spring Boot 2.7.18 BOM 默认 snakeyaml 1.30，存在 CVE-2022-38751/38752/41854（不可信 YAML 解析栈溢出 / DoS）。根 POM 在 `spring-boot-dependencies` 之前显式声明 `snakeyaml:1.33`（1.x 末版含 DoS 修复，Boot 用于 `application.yml` 解析）覆盖。
- **基线迁移**：1.x 的依赖覆盖不能恢复 Boot 2.7 的上游 OSS 支持，也不构成持续安全修复承诺。`2.0.0` 目标为 **Spring Boot 4.1**；迁移步骤、基线选择和 1.x 维护边界统一见[开发计划](development-plan.md)。迁移时逐项审核这些历史 pin，优先恢复目标 BOM 管理，只保留有验证依据的覆盖。

## Maven Profiles

### 生成 SBOM

```powershell
$env:JAVA_HOME='C:\Program Files\graalvm-jdk-17.0.11+7.1'
& 'C:\Users\qq951\.m2\wrapper\dists\apache-maven-3.9.9-bin\4nf9hui3q3djbarqar9g711ggc\apache-maven-3.9.9\bin\mvn.cmd' -Psbom -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom
```

输出位置：

- `target/netty-spring-sbom.json`
- `target/netty-spring-sbom.xml`

### 漏洞扫描

```powershell
$env:JAVA_HOME='C:\Program Files\graalvm-jdk-17.0.11+7.1'
& 'C:\Users\qq951\.m2\wrapper\dists\apache-maven-3.9.9-bin\4nf9hui3q3djbarqar9g711ggc\apache-maven-3.9.9\bin\mvn.cmd' -Pdependency-scan -DskipTests package org.owasp:dependency-check-maven:12.2.1:aggregate
```

输出位置：

- `target/dependency-check/dependency-check-report.html`
- `target/dependency-check/dependency-check-report.json`
- `target/dependency-check/dependency-check-report.sarif`

`dependency-scan` 默认 `failBuildOnCVSS=7.0`、`failOnError=true`，高危及以上漏洞或扫描错误都会导致失败。命令先执行 `package`，确保扫描当前 reactor 构建的实际制品；`-DskipTests` 只避免重复执行已由独立测试门禁完成的测试，不跳过扫描。第一次扫描需要下载漏洞数据库，耗时会明显长于普通测试；建议配置 `NVD_API_KEY` 环境变量和可复用缓存。

```powershell
$env:NVD_API_KEY='replace-with-ci-secret'
```

CI 有 `NVD_API_KEY` 时使用 NVD API；未配置时使用 [Dependency-Check 官方维护的 NVD 镜像](https://dependency-check.github.io/DependencyCheck/data/mirrornvd.html)，参数为 `-DnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz`。镜像按官方说明尽力每日更新，不能仅因 URL 可访问就认为数据有效：扫描前必须读取同目录 `cache.properties`，验证 `lastModifiedDate` 距当前 UTC 不超过 **72 小时**，且不能超前 **5 分钟**。下载失败、时间戳缺失/无时区/无效、数据过期或异常超前均阻断扫描，不降级为空库或跳过门禁。本地使用该镜像时同样先完成新鲜度检查，并记录时间戳；CI 的检查实现集中在 `.github/workflows/ci.yml`。

Dependency-Check data cache 已固定到 `${settings.localRepository}/../dependency-check-data`，默认 Maven 环境下即 `~/.m2/dependency-check-data`，便于本地和 CI 复用同一个缓存约定。如果本地首次扫描长时间停留在漏洞库初始化阶段，可以先确认该缓存是否可复用，再放到 CI 中执行完整扫描。企业安全发布不得因本地首次拉库超时而跳过该门禁。

## GitHub Actions 门禁

仓库提供 `.github/workflows/ci.yml`：

- `Maven Test`：push 和 pull request 默认执行全量 `mvn test`。
- `Generate SBOM`：测试通过后生成 `target/netty-spring-sbom.json` 和 `target/netty-spring-sbom.xml`，并上传为 `netty-spring-sbom` artifact。
- `Dependency Scan`：只在 `workflow_dispatch`、每周定时任务或 `v*` tag push 时执行，用于发布前门禁；优先使用 `NVD_API_KEY` secret，未配置时使用通过上述新鲜度检查的官方镜像，并缓存 `~/.m2/dependency-check-data`。扫描失败时也尝试上传已有报告，便于告警 triage。

每次发布须记录 `Dependency Scan` job 的实际结果；失败或未执行时保留未完成状态，不能写成验证通过。企业安全发布还须完成漏洞处置门槛。普通 PR 不默认跑漏洞库扫描。

## 告警处理规则

1. 高危及以上漏洞必须在企业安全发布前处理：优先升级依赖，其次记录无法升级原因和规避措施。
2. 中低危漏洞需要记录影响范围：是否只在 test scope、是否可被当前运行路径触发、是否已有上游修复版本。
3. Dependabot 与 Dependency-Check 结果不一致时，以更严格的结论进入人工 triage。
4. 误报只能写入 `dependency-check-suppressions.xml`，不能通过降低 `failBuildOnCVSS` 绕过。
5. 每条 suppression 必须包含依赖/CVE、理由和后续清理计划；长期 suppression 需要在发布前重新复核。

## 企业安全发布门槛

企业安全发布前至少需要完成：

- `mvn test`
- `mvn -Psbom -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom`
- `mvn -Pdependency-scan -DskipTests package org.owasp:dependency-check-maven:12.2.1:aggregate`（无 API key 时先验证镜像新鲜度并附加 `-DnvdDatafeedUrl=...`）
- GitHub Actions `CI` workflow 中的 `Maven Test`、`Generate SBOM` 和发布门禁 `Dependency Scan`
- GitHub Dependabot 或等效扫描告警已处理、记录或明确延期

如果依赖扫描因为网络或漏洞数据库不可用而失败，本次只能作为开发提交、功能/稳定性发布或 RC 验证，不能直接声明为企业安全发布。
