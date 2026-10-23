# 依赖治理与供应链门禁

更新时间：2026-10-23

## 目标

- 让后续企业安全发布前具备可重复执行的依赖清单和漏洞扫描入口。
- 把 GitHub Dependabot 或等效工具提示的问题纳入发布判断，而不是只依赖人工记忆。
- 普通开发命令保持轻量，依赖扫描和 SBOM 生成只在显式 profile 下运行。
- 本地普通构建不自动扫描；当前 CI 对所有 `v*` tag 执行 Dependency Scan，功能补丁也不能宣称跳过该 job 后“全部 CI 通过”。扫描结果与尚未完成项记录到对应发布说明。

## 当前依赖基线与覆盖

当前开发版本为 `2.0.0-SNAPSHOT`，根 POM 锁定 **Spring Boot 4.1.2 / JDK 17**，编译使用 `--release 17` 和 `-parameters`。Spring Framework、Netty、SLF4J、Log4j、Lettuce、Micrometer、JUnit、Mockito 和 Testcontainers 统一由 Boot BOM 管理；旧 Spring 5、Netty 4.1、Jackson 2 和日志组件等独立覆盖已清理，当前必要的安全覆盖单列如下。历史版本及漏洞处置依据集中保留在 [1.10.1 发布说明](release-notes-1.10.1.md)，本轮依赖与安全验证事实见 [2.0.0 发布说明](release-notes-2.0.0.md#dependency-scan-evidence)，迁移状态见[开发计划](development-plan.md)。

当前保留的显式版本如下；升级 Boot 时逐项复核，BOM 纳入对应修复后移除安全覆盖。

| 依赖 | 当前版本与原因 |
|---|---|
| `tools.jackson:jackson-bom` | **3.1.8**，在 Boot BOM 之前导入，覆盖 Boot 4.1.2 的 3.1.7。修复 CVE-2026-110879 多态类型校验绕过；影响包含基于注解的类名多态，不能仅因未启用 default typing 就排除风险。依据：[Jackson 官方安全公告](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-m8xf-2cr7-77ph)。 |
| `org.yaml:snakeyaml` | **2.7**，覆盖 Boot 4.1.2 的 2.6，保留移除绕过 `TagInspector` 的 `CompactConstructor` / `PackageCompactConstructor` 的修复；YAML 配置加载仍需回归。依据：[SnakeYAML 变更记录](https://github.com/snakeyaml/snakeyaml/blob/master/src/changes/changes.xml)。 |
| `ch.qos.logback:logback-core` / `logback-classic` | **1.6.5**，成对覆盖 Boot 4.1.2 的 1.5.38，修复 CVE-2026-19880 / CVE-2026-104721：不可信 MDC discriminator 经 `SiftingAppender` 进入文件路径；1.6.3 首轮修复仍可绕过。两个组件必须同版，SLF4J 继续由 Boot 管理为 2.0.20。官方要求 Java 11+ / SLF4J 2.0.x，满足本项目基础版本要求；具体打包、扫描及日志兼容验证以发布说明证据为准。依据：[Logback 官方修复记录](https://logback.qos.ch/news.html#1.6.5)、[1.6.5 官方 POM](https://github.com/qos-ch/logback/blob/v_1.6.5/pom.xml)。 |
| `org.eclipse.angus:angus-activation` | **2.0.3**，Boot BOM 未管理此实现；作为 `netty-spring-web` 的 runtime 依赖，为 MIME 类型解析提供 Jakarta Activation 实现。`jakarta.activation-api` 版本由 Boot 管理。 |
| `io.nats:jnats` | **2.20.4**，Boot BOM 未管理的可选 NATS 客户端，沿用既有传输基线；使用 NATS 的应用需显式引入，Redis 默认路径不应依赖它。 |
| `org.projectlombok:lombok` | **1.18.48**，与 Boot 4.1.2 一致；根属性同时供依赖和编译器 annotation processor 使用，避免处理器与依赖版本不一致。 |

Runtime 继续按需引入 Netty 模块，不使用 `netty-all`。每次变更依赖后检查 `dependency:tree` / effective POM 与实际制品，尤其避免 Spring、Netty、Jackson 混版。上述版本选择不代表 `2.0.0` 漏洞扫描已经通过；SBOM、扫描结果、残余告警及远端 CI 均须按本版实际结果记录。

## Maven Profiles

以下命令在仓库根目录执行，`JAVA_HOME` 指向待验证的 JDK。Windows 使用 `.\mvnw.cmd`，Linux/macOS 使用 `bash ./mvnw`；无需依赖维护者本机 Maven 安装路径。

### 生成 SBOM

```powershell
.\mvnw.cmd -Psbom -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom
```

输出位置：

- `target/netty-spring-sbom.json`
- `target/netty-spring-sbom.xml`

### 漏洞扫描

```powershell
.\mvnw.cmd -Pdependency-scan -DskipTests clean package org.owasp:dependency-check-maven:12.2.2:aggregate
```

输出位置：

- `target/dependency-check/dependency-check-report.html`
- `target/dependency-check/dependency-check-report.json`
- `target/dependency-check/dependency-check-report.sarif`

`dependency-scan` 默认 `failBuildOnCVSS=7.0`、`failOnError=true`，高危及以上漏洞或扫描错误都会导致失败。本地依赖变更后先执行 `clean package`，并核对 fat jar 内嵌依赖与 SBOM 一致：非 clean 构建可能在依赖解析已更新时仍保留旧的 repackage 制品。执行 clean 前将需要留档的测试/扫描报告复制到 target 之外。CI 使用干净 checkout，可直接 package；`-DskipTests` 只避免重复执行已由独立测试门禁完成的测试，不跳过扫描。第一次扫描需要下载漏洞数据库，耗时会明显长于普通测试；建议配置 `NVD_API_KEY` 环境变量和可复用缓存。

```powershell
$env:NVD_API_KEY='replace-with-ci-secret'
```

CI 有 `NVD_API_KEY` 时使用 NVD API；未配置时使用 [Dependency-Check 官方维护的 NVD 镜像](https://dependency-check.github.io/DependencyCheck/data/mirrornvd.html)，参数为 `-DnvdDatafeedUrl=https://dependency-check.github.io/DependencyCheck_Builder/nvd_cache/nvdcve-{0}.json.gz`。镜像按官方说明尽力每日更新，不能仅因 URL 可访问就认为数据有效：扫描前必须读取同目录 `cache.properties`，验证 `lastModifiedDate` 距当前 UTC 不超过 **72 小时**，且不能超前 **5 分钟**。下载失败、时间戳缺失/无时区/无效、数据过期或异常超前均阻断扫描，不降级为空库或跳过门禁。本地使用该镜像时同样先完成新鲜度检查，并记录时间戳；CI 的检查实现集中在 `.github/workflows/ci.yml`。

Dependency-Check data cache 已固定到 `${settings.localRepository}/../dependency-check-data`，默认 Maven 环境下即 `~/.m2/dependency-check-data`，便于本地和 CI 复用同一个缓存约定。如果本地首次扫描长时间停留在漏洞库初始化阶段，可以先确认该缓存是否可复用，再放到 CI 中执行完整扫描。企业安全发布不得因本地首次拉库超时而跳过该门禁。

## GitHub Actions 门禁

仓库提供 `.github/workflows/ci.yml`：

- `Maven Test (JDK 17/21/25)`：push 和 pull request 默认在三个 JDK 上通过 Maven Wrapper 执行全量 `test`，编译基线仍为 17；矩阵配置不代表三个运行结果已通过。
- `Generate SBOM`：测试通过后生成 `target/netty-spring-sbom.json` 和 `target/netty-spring-sbom.xml`，并上传为 `netty-spring-sbom` artifact。
- `Dependency Scan`：只在 `workflow_dispatch`、每周定时任务或 `v*` tag push 时执行，用于发布前门禁；优先使用 `NVD_API_KEY` secret，未配置时使用通过上述新鲜度检查的官方镜像，并缓存 `~/.m2/dependency-check-data`。扫描失败时也尝试上传已有报告，便于告警 triage。

测试 job 汇总 Surefire XML，要求 tests 大于零且 failures/errors/skipped 均为零，避免因 Docker 不可用而静默跳过集成测试；每个 JDK 的报告分别上传。每次发布须记录 `Dependency Scan` job 的实际结果；失败或未执行时保留未完成状态，不能写成验证通过。企业安全发布还须完成漏洞处置门槛。普通 PR 不默认跑漏洞库扫描。

## 告警处理规则

1. 高危及以上漏洞必须在企业安全发布前处理：优先升级依赖，其次记录无法升级原因和规避措施。
2. 中低危漏洞需要记录影响范围：是否只在 test scope、是否可被当前运行路径触发、是否已有上游修复版本。
3. Dependabot、Dependency-Check 与官方安全公告结果不一致时，以更严格的结论进入人工 triage；扫描器未报告不能替代对官方受影响版本范围的核对。
4. 误报只能写入 `dependency-check-suppressions.xml`，不能通过降低 `failBuildOnCVSS` 绕过。
5. 每条 suppression 必须包含依赖/CVE、理由和后续清理计划；长期 suppression 需要在发布前重新复核。

## 企业安全发布门槛

企业安全发布前至少需要完成：

- `.\mvnw.cmd test`
- `.\mvnw.cmd -Pdependency-scan -DskipTests clean package org.owasp:dependency-check-maven:12.2.2:aggregate`（先归档测试报告；无 API key 时先验证镜像新鲜度并附加 `-DnvdDatafeedUrl=...`）
- `.\mvnw.cmd -Psbom -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom`（在 clean 构建之后生成，并核对实际制品）
- GitHub Actions `CI` workflow 中的三项 `Maven Test`、`Generate SBOM` 和发布门禁 `Dependency Scan`
- GitHub Dependabot 或等效扫描告警已处理、记录或明确延期

如果依赖扫描因为网络或漏洞数据库不可用而失败，本次只能作为开发提交、功能/稳定性发布或 RC 验证，不能直接声明为企业安全发布。
