# 版本发布检查清单

更新时间：2026-06-08

## 适用范围

- 适用于所有 `netty-spring` 版本发布（功能/稳定性发布与企业安全发布）。
- 目标是保证发布动作可重复执行，而不是依赖一次性的人工记忆。
- 历史 `1.0.x`–`1.7.x` 的版本特定确认项见对应 `docs/release-notes-*.md`，本清单只保留可复用的通用流程与最新版本口径。

## 版本类型

- **功能/稳定性发布**（默认）：核心要求是全量测试通过、Starter 兼容、配置文档与代码一致、SBOM 生成和基础 CI 链路成功；当前不把依赖漏洞扫描作为阻塞项。
- **企业安全发布**（`2.0.0` 之后引入）：在功能/稳定性要求之上，额外完成 Dependency-Check / Dependabot triage 闭环、CORS / 握手鉴权 / TLS 策略说明与管理端点访问控制确认。

## 通用发布前检查

1. **版本号与文档**：根 `pom.xml` 切到目标版本（覆盖全部 11 模块）。**逐行走「文档同步矩阵」（见文末）—— 这是防遗忘的单一权威清单；不要凭记忆更新。** 历史上 `development-plan.md` 漏更过 GA 状态，矩阵就是为了杜绝这类 lockstep-miss。
2. **全量测试**：执行 `mvn test`，全部模块 SUCCESS（`1.8.0` 起为 11 个模块）。
3. **SBOM**：`mvn -Psbom -DskipTests org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom`；CI 发布需确认 `Maven Test` + `Generate SBOM` job 成功。
4. **Starter & Demo 回归**：四个 Starter（`netty-web` / `netty-webmvc` / `netty-websocket` / `demo-netty-web`）的集成测试必须通过；demo 启动 smoke 通过。
5. **工程化边界复核**：
   - Starter 启动失败抛 Spring Boot 异常，不直接终止 JVM。
   - `NettyServerBootstrap.stop()` 在 repeated stop、异常 stop、startup failure 后都能完成资源清理。
   - `MessageSenderSupport` 在无 websocket mapping、stop/start、停机联动下行为可预测。
6. **稳定性门槛**（自 `1.1.0` 起持续生效）：
   - 静态文件服务不能通过 `..`、URL 编码或路径规范化绕过根目录。
   - HTTP request line / header / chunk / body 上限、`read-timeout` / `write-timeout` / `idle-timeout` 有显式配置入口与回归测试。
   - 启用 SSL 时证书和私钥在启动期校验；生产环境显式配置 `http.ssl.protocols` 与 `http.ssl.ciphers`。
   - WebSocket `allowed-origins` 在生产部署中显式评估，不默认放开跨站握手。
   - MVC / 静态文件 / WebSocket 写失败与过载拒绝有统一日志、关闭策略和指标；`management.enable=true` 只在受保护网络下暴露。
   - handler/sender 线程池配置有启动期校验，非法容量和 `max < core` 不会静默兜底。
7. **可观测性门槛**（自 `1.7.0` 起）：
   - `micrometer-core` 与 `spring-boot-actuator` 为 optional 依赖，缺失时自动退化。
   - 新增 Micrometer 指标命名遵循 `netty.<component>.*`，标签卡死在框架枚举（如 `CloseReason`）以避免无界基数。
   - 新增可观测能力同步进 `docs/api-guide.md` §10 与对应配置文档。
8. **审计闭环**（自 `1.7.0` 起，minor 版本必做）：
   - 发布前至少一轮独立代码审计（建议参考 `1.7.0` 的 4 路并行审计 + 对抗式验证模型）。
   - 发现的正确性 / 资源 / 安全问题在发版前清零；保留审计纪要在发布说明中体现。
9. **发布说明**：`docs/release-notes-<version>.md` 描述版本定位、改动清单、新增测试、升级指南、向后兼容性声明。

## 企业安全发布附加项

1. 发布前完成依赖漏洞扫描，处理或记录 GitHub Dependabot / 等效扫描告警；高危及以上漏洞或未 triage 的扫描失败不得进入企业安全发布 tag。
2. Dependency-Check 误报必须写入 `dependency-check-suppressions.xml` 并说明原因，不能通过降低扫描门槛绕过。
3. CI 或发布机必须为企业安全发布门禁配置 `NVD_API_KEY`，并复用 `${settings.localRepository}/../dependency-check-data` Dependency-Check 缓存。
4. 握手鉴权、CORS / Origin、TLS 协议/套件和管理端点暴露策略需要在发布说明中明确。

## 发布动作

1. 工作树只保留本次发布相关文件，不带入 `.m2/`、本地缓存或实验文件。
2. 创建发布提交（版本号 + 发布说明 + 开发计划状态）。
3. 创建对应 annotated tag，例如 `v1.7.0`、`v1.8.0-RC1`。
4. 推送分支和 tag 到 `origin`。
5. Maven 制品发布（如需）：在凭据已配置的 CI 或本机执行 `mvn deploy`，目标仓库见根 `pom.xml` `distributionManagement`。

## 发布后

1. 将开发线切到下一个 `-SNAPSHOT` 版本（若采用 SNAPSHOT 流程）。
2. 更新 [开发计划](development-plan.md) 当前状态与下一阶段目标。
3. 如果本次补了新的稳定性 / 可观测性 / 审计边界，回写本清单"通用发布前检查"对应小节，避免后续回归再次依赖口头约定。

## 权威记录在哪里（避免重复 = 避免漂移）

**每个版本的逐项完成确认不在本清单重复** —— 它们的权威记录是 `docs/release-notes-<version>.md`。本清单只保留**可复用的流程** + 下面的**文档同步矩阵**。历史教训：本清单曾维护一份「最新版本口径」per-release 日志，结果它停在 `1.9.0-RC1` 再没更新（GA、RC2–RC20 全漏），自己变成了 stale 源。已删除，改为指针。

- 当前最新稳定版 / 在研版本：见 `docs/development-plan.md` 顶部「当前结论」（**该处是版本状态的单一权威源**；其余文档应指向它，不重述完整 feature 列表）。
- 每个已发布版本的改动/测试/升级/兼容性细节：见对应 `docs/release-notes-<version>.md`。
- 完整周期回顾：见 `docs/1.9.x-cycle-retrospective.md`。

## 📋 文档同步矩阵（防遗忘 —— 每次发布逐行走）

> 版本号 / 状态 / 测试数 这类字符串散落在多个文档,**必须 lockstep 更新,漏一个就 stale**。下表是完整的承载位置清单。发布时**逐行核对**,不要凭记忆。

| # | 文件 | 位置 | 承载内容 | 何时更新 |
|---|---|---|---|---|
| 1 | 11 个 `pom.xml` | `<version>` | 版本号本身 | 每次发布/RC（`sed` 全量改） |
| 2 | `README.md` | EN "Current Status"（搜 `Latest stable`）+ 中文「当前阶段」（搜 `最新稳定版`） | 最新稳定版 + 测试数 + feature 摘要 | 每次 GA |
| 3 | `README.md` | Maven 坐标 EN + 中文 + cluster starter（搜 `<version>`） | 依赖示例版本 | 每次 GA |
| 4 | `README.md` | Central versions 列表（搜 `1.4.0`、`1.6.2`...） | 已发布版本清单 | 每次 GA |
| 5 | `README.md` | 性能基准表 | 测试/跑分数字 | 仅 benchmark 刷新时 |
| 6 | `docs/development-plan.md` | 「当前结论」首段 + 「历史版本一览」表 | **单一权威状态源** + 历史 | 每次发布/RC |
| 7 | `docs/api-guide.md` | Maven 依赖示例（搜 `<version>`）+ §9/§11 配置参考 | 依赖版本 + 新配置项 | GA + 新配置项时 |
| 8 | `docs/cluster-design.md` | scope 表（✅/⏳ 标记） | feature 实现状态 | 集群 feature 落地时 |
| 9 | `docs/release-notes-<version>.md` | 标题状态行 + 全文 | 该版本权威记录（新建文件） | 每次发布/RC |
| 10 | `.claude/CLAUDE.md` | Current version 行 | 项目上下文 | 每次 GA |
| 11 | `docs/release-checklist.md`（本文件） | 顶部「更新时间」 | checklist 自身时效 | 每次走完 checklist |
| 12 | `netty-websocket-cluster-spring-boot-starter/.../META-INF/additional-spring-configuration-metadata.json` | `properties` 数组 | **每个新 `cluster.*` 配置项的 IDE 自动补全元数据**（描述+默认值） | 新增任何集群配置项时（教训：RC4a–RC4d 的 `cluster.mesh.*` 14 个键漏到了 1.10.0 GA 才补） |

**自检命令**（发布前跑,确认没有遗漏的旧版本号）：
```bash
# 找出仍指向上一个版本的文档（把 OLD 换成上一稳定版，如 1.9.0）
grep -rn "OLD" README.md docs/*.md .claude/CLAUDE.md | grep -v release-notes-
# 找出 pom 残留的上一个版本/RC
grep -rn "1\.10\.0-RC" --include=pom.xml .   # 应只剩目标版本
```

> 维护原则:**新增一处承载版本/状态字符串的文档,必须同步把它加进本矩阵**——否则它就是下一个 stale 源。
