# 依赖治理与供应链门禁

更新时间：2026-04-26

## 目标

- 让 `1.1.0` 正式版发布前具备可重复执行的依赖清单和漏洞扫描入口。
- 把 GitHub Dependabot 或等效工具提示的问题纳入发布判断，而不是只依赖人工记忆。
- 普通开发命令保持轻量，依赖扫描和 SBOM 生成只在显式 profile 下运行。

## Maven Profiles

### 生成 SBOM

```powershell
$env:JAVA_HOME='C:\Program Files\graalvm-jdk-17.0.11+7.1'
& 'C:\Users\qq951\.m2\wrapper\dists\apache-maven-3.9.9-bin\4nf9hui3q3djbarqar9g711ggc\apache-maven-3.9.9\bin\mvn.cmd' -Psbom -DskipTests package org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom
```

输出位置：

- `target/netty-spring-sbom.json`
- `target/netty-spring-sbom.xml`

### 漏洞扫描

```powershell
$env:JAVA_HOME='C:\Program Files\graalvm-jdk-17.0.11+7.1'
& 'C:\Users\qq951\.m2\wrapper\dists\apache-maven-3.9.9-bin\4nf9hui3q3djbarqar9g711ggc\apache-maven-3.9.9\bin\mvn.cmd' -Pdependency-scan org.owasp:dependency-check-maven:12.2.1:aggregate
```

输出位置：

- `target/dependency-check/dependency-check-report.html`
- `target/dependency-check/dependency-check-report.json`
- `target/dependency-check/dependency-check-report.sarif`

`dependency-scan` 默认 `failBuildOnCVSS=7.0`，即高危及以上漏洞会阻断正式发布。第一次扫描需要下载漏洞数据库，耗时会明显长于普通测试；CI 环境建议配置 `NVD_API_KEY` 环境变量和可复用缓存。

```powershell
$env:NVD_API_KEY='replace-with-ci-secret'
```

如果本地首次扫描长时间停留在漏洞库初始化阶段，可以先确认 Maven 本地仓库中的 Dependency-Check data cache 是否可复用，再放到 CI 中执行完整扫描。不能因为本地首次拉库超时就跳过正式版发布门禁。

## 告警处理规则

1. 高危及以上漏洞必须在正式版发布前处理：优先升级依赖，其次记录无法升级原因和规避措施。
2. 中低危漏洞需要记录影响范围：是否只在 test scope、是否可被当前运行路径触发、是否已有上游修复版本。
3. Dependabot 与 Dependency-Check 结果不一致时，以更严格的结论进入人工 triage。
4. 误报只能写入 `dependency-check-suppressions.xml`，不能通过降低 `failBuildOnCVSS` 绕过。
5. 每条 suppression 必须包含依赖/CVE、理由和后续清理计划；长期 suppression 需要在发布前重新复核。

## 发布门槛

`1.1.0` 正式版发布前至少需要完成：

- `mvn test`
- `mvn -Psbom -DskipTests package org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom`
- `mvn -Pdependency-scan org.owasp:dependency-check-maven:12.2.1:aggregate`
- GitHub Dependabot 或等效扫描告警已处理、记录或明确延期

如果依赖扫描因为网络或漏洞数据库不可用而失败，本次只能作为开发提交或 RC 验证，不能直接打正式发布 tag。
