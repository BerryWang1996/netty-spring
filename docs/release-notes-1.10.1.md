# Release Notes — 1.10.1

更新时间：2026-10-24

## 发布状态（本版唯一记录）

**`1.10.1` 已发布到 Maven Central，原 deployment 已为 `PUBLISHED`，全部十个坐标的 68 份公开制品和签名均已下载并核对一致。** 标签 `v1.10.1` 仍指向 `002358b`，发布账号为 `BerryWang1996`；本次仅确认原发布结果，没有重新上传或移动标签。测试、SBOM、单机/双节点冒烟和最终 34 份签名验证完成；正式标签扫描仍有 43 项 Boot/Spring 告警，其中 21 项 CVSS ≥ 7.0。本版为公开披露风险的功能/稳定性补丁，不能作为企业安全发布或宣称全部 CI 绿色。主线 `2.0.0` 已完成 Boot 4.1 迁移并发布。

公开下载入口：[组合 starter](https://repo.maven.apache.org/maven2/io/github/berrywang1996/netty-web-spring-boot-starter/1.10.1/)、[集群 starter](https://repo.maven.apache.org/maven2/io/github/berrywang1996/netty-websocket-cluster-spring-boot-starter/1.10.1/)。当前教程与默认依赖示例使用 `2.0.0`；仍使用 Boot 2.7 的应用应结合本页的兼容边界与剩余风险接入 `1.10.1`。当前公开版本与路线统一见[开发计划](development-plan.md)。

## 修复与兼容性

- 修复集群 starter 在没有可选 `jnats` 依赖的默认 Redis 部署中启动失败（`NoClassDefFoundError: io/nats/client/...`）。将带 NATS 类型的 bean 方法隔离到类级条件配置中；NATS 显式启用却缺依赖时仍快速报错。修复提交：`b785fde`，附三项回归测试。
- 弃用从未有注入处理器的 `@AutowiredMessageSender`，保留注解以维持源码兼容。业务应使用构造器注入 `MessageSender`；同步修正文档中的握手参数说明。修复提交：`0a6c47e`。
- Docker demo 从构建目录存在的版本化 jar 中选最新构建，避免版本升级后运行旧 jar。
- 修正纯装配测试的空闲端口探测竞争：直接绑定端口 `0`，由操作系统分配；保留负端口失败与启动状态断言。
- 修正可选依赖测试的前置条件提示：无可用 Redis 时不会提前调用 `uri()` 导致异常，按原有约定跳过；正式发布验证必须报告跳过数。

本版保留 **Spring Boot 2.7.18 / Spring Framework 5.3.39 / JDK 17** 基线，将 Netty 升级至 `4.1.139.Final`、Jackson BOM 升级至 `2.18.12`、SnakeYAML 升级至 `2.7`、Log4j BOM 升级至 `2.25.5`，已完成本地回归及冒烟。配置键、消息线格式与投递语义保持兼容；未增加 Boot 3/4 支持。Boot 4.1 迁移属于 `2.0.0`，步骤统一在[开发计划](development-plan.md)。上游 2.7 OSS 支持已结束，本版不是上游依赖的全面安全修复，详见下方安全处置记录与[依赖治理](dependency-governance.md)。

兼容性以现有配置、协议和回归样本为依据。Netty 对非法 MIME Content-Type、Jackson 对异常或过度复杂输入的校验更严格；不保证此前被接受的所有异常输入仍会被接受。

## 验证记录

| 检查 | 结果 |
|---|---|
| 11 个 POM 版本一致 | 已统一为 1.10.1 |
| JDK 17 全 reactor verify | 升级后：650 tests / 0 failures / 0 errors / 0 skipped，11 个模块 SUCCESS；Redis、Redis Cluster、NATS/JetStream 与 mesh 测试已执行 |
| CycloneDX SBOM | 升级后的 JSON/XML 已生成；根组件版本 1.10.1，53 个依赖组件；四组升级版本解析正确 |
| 默认无 jnats 的双节点 Docker 广播 | 本地 PASS；alice 在 node-a、bob 在 node-b，收到带正确 originNode 的广播；健康 UP。[远端 Docker Demo Smoke #39962106347](https://github.com/BerryWang1996/netty-spring/actions/runs/39962106347) 成功 |
| 单机 demo / 外部 YAML | 真实外部 YAML 配置 `server.netty.port=8082`，应用成功启动；健康 UP，`/ws/test` 真实 WebSocket 回显 PASS，验证 SnakeYAML 2.7 配置加载路径 |
| 发布制品 sources / Javadoc / GPG | 10 个发布模块、34 个发布文件及其分离签名已上传；最终 deploy 后的 34 份签名独立验签通过，发布包 SHA-256 全量匹配。3 个无源码 starter 不生成 sources.jar，其余源码/Javadoc 附件齐全 |
| Dependency Scan / 漏洞 triage | Dependency-Check 12.2.2 复扫生成 HTML/JSON/SARIF，无 analysisExceptions；43 项 Boot/Spring 告警，21 项 CVSS ≥ 7.0，扫描失败。四组升级依赖的原告警已不再出现；未添加 suppression。另由 Dependabot 补充 Logback 6 项，详见下文 |
| Git 提交、v1.10.1 tag、远端 CI | `v1.10.1` → `002358b`；[正式标签 CI #39963415642](https://github.com/BerryWang1996/netty-spring/actions/runs/39963415642)：650 tests / 0 failures / 0 errors / 0 skipped，SBOM 通过，Dependency Scan 因上述漏洞失败，报告已上传。标签与已测候选 `52206a5` 仅发布记录不同 |
| Central 上传及发布 | 原 deployment `fedd63c8-f139-4a40-a0ef-9a9a4af1a4bc` 已 `PUBLISHED`；2026-10-24 从公开仓库匿名下载十个坐标的 34 份制品与 34 份签名，全部 HTTP 200、长度和 SHA-256 一致，无待完成文件 |
| 独立 Maven 消费者 | 全新空缓存、匿名 Central-only 解析和编译成功；最小单机运行 8/8 检查通过，无 jnats。应用 Boot parent 的实际依赖覆盖与本版 reactor 基线分开记录如下 |

环境：GraalVM JDK 17.0.11、Maven 3.9.16、Docker Desktop；2026-10-23 执行。首轮 clean 构建暴露装配测试的端口竞争，修复后重新执行完整 `verify` 通过。项目 demo fat jar 的依赖清单确认没有 jnats。Docker 验证使用独立 Compose 项目 `netty-spring-1101-check` 和本机 18080/18081 端口，结束后已清理本次容器及网络。

### Central 下载确认（2026-10-24）

此前在发布请求后立即检查时，Portal 为 `PUBLISHING`，公开文件返回 404，原始记录保留在 `.m2/central-download-verification-1101.json`。本次复核同一 deployment 已为 `PUBLISHED`；没有重新 deploy、重新签名或替换已发布版本。Portal 附带的 `Deployment components info not found` 信息不提供组件清单，因此以实际公开文件的完整下载和字节核对作为可用性证据。

从原冻结包 `.m2/central-bundle-1.10.1.zip` 安全提取 34 份 payload 和 34 份 ASC，包 SHA-256 为 `d667f6d0a5671927559d55c19939ad9e819e7b4995e6043f36fd55a87ae066dc`；四组 MD5/SHA-1/SHA-256/SHA-512 校验文件各 34 份全部匹配。34 份 payload 与原下载清单一致，十个 POM 的 GAV 均为本版本且与 `v1.10.1` 一致（仅规范换行）。全部 34 份签名以主指纹 `09CC1D729D9E1CBBA18DE39E8B24A2A210E4168C` 实际验签通过，签名创建时间为最终 deploy 对应的 `2026-10-23T13:42:34Z` 至 `13:42:41Z`。核验归档为 `.m2/1101-publication-verification-2026-10-24T01-13-28-963Z/`；没有使用当前 2.0.0 的 target 产物代替旧版本。

随后匿名读取 `https://repo.maven.apache.org/maven2/`，十个坐标的 POM、主包、适用的 sources/Javadoc 及全部 ASC 共 **68/68 文件**均在首轮 HTTP 200，长度和 SHA-256 与上述冻结包逐项一致。公开证据为 `.m2/central-public-1.10.1-2026-10-24T01-14-14-519Z-34592/verification.json`，`complete=true`、`credentialsUsed=false`、`failedOrPending=0`。此次核对确认仓库可下载及内容一致，不改变历史漏洞扫描失败和风险披露。

另建普通 `spring-boot-starter-parent:2.7.18` 应用，使用 JDK `17.0.11`，显式依赖普通 Boot starter、`netty-web-spring-boot-starter:1.10.1` 与 `netty-websocket-cluster-spring-boot-starter:1.10.1`。从全新空 Maven 缓存匿名解析、编译成功，共取得 40 个运行库；7 个项目 jar、7 个子 POM 和父 POM 均与上述公开文件的长度、SHA-256 和 Central 下载来源标记一致。Maven 日志无 WARNING、校验和或传输错误。1 个夹具源码编译为 3 个 Java 17 class，实际运行的 8 项检查全部通过：无 jnats 的单机自动装配、健康 UP、文本/JSON Bean 精确回显及各自 code 1000 关闭、JVM 正常退出 0、端口 18116 释放。没有使用 Docker 或修改既有 MySQL 容器；本次单机检查不替代上方已完成的集群演练。

**应用依赖管理边界：** 本次刻意使用普通 Boot parent，未额外复制本库 reactor 的版本覆盖。实际解析为 Spring `5.3.31`、Netty `4.1.101.Final`、Jackson `2.13.5`、SnakeYAML `1.30`、Logback `1.2.12`、Log4j `2.17.2`、SLF4J `1.7.36`、Lettuce `6.1.10.RELEASE`，因此不能将“starter 已升级到 1.10.1”当作应用传递依赖已采用上文安全补丁版本的证明。继续使用 1.x 的应用须检查自己的 effective POM、依赖树与打包结果，并在自身 dependencyManagement 中维护所需兼容覆盖；即使与发布 reactor 对齐，本页已披露的 Boot/Spring/Logback 残余风险仍保留。该消费者验证只证明公开制品可解析、编译和最小运行，不是新的安全扫描。

消费者完整证据（含独立缓存、源码/class、脚本快照、输入清单、依赖树和原始日志）冻结于 `.m2/consumer-central-1101/runs/2026-10-24T01-15-05-239Z/`，`verification-summary.json` 为汇总，运行结果在 `smoke-2026-10-24T01-18-29-826Z/result.json`；1,893 文件清单的 SHA-256 为 `42de79c01de31a9b76805efc0f3d83cd16afde39643015b054c38c7ac5d9291a`。

验证命令（使用 JDK 17；新环境先 clean）：

```sh
mvn -B -ntp clean verify -Psbom org.cyclonedx:cyclonedx-maven-plugin:2.9.1:makeAggregateBom
mvn -B -ntp -P release -pl '!demo-netty-web-spring-boot-starter' -DskipTests verify
docker compose -f docker-demo/docker-compose.yml up -d --build
# docker-demo/ 下安装依赖后运行 node smoke.js
```

## 本版安全处置记录

### 扫描证据与当前结论

2026-10-23 21:23（UTC+8）完成首次扫描，兼容依赖升级后于 21:35 使用 Dependency-Check **12.2.2** 复扫，生成 `target/dependency-check/dependency-check-report.{html,json,sarif}`。复扫报告无 `analysisExceptions`，保留 **43 项 Boot/Spring 告警，其中 21 项 CVSS ≥ 7.0**；Netty/Jackson/SnakeYAML/Log4j 的原告警已不再出现。扫描阈值维持 **CVSS 7.0**，`failOnError=true`，没有降低阈值或禁用扫描。

本机未配置 NVD API key，首次扫描从 [Dependency-Check 官方维护的 NVD 镜像](https://dependency-check.github.io/DependencyCheck/data/mirrornvd.html) 导入数据；`cache.properties` 的 `lastModifiedDate=2026-10-22T15:44:59Z` 与报告内 NVD Cache Last Modified 一致，满足 72 小时新鲜度要求。本地复扫复用这份缓存；远端于 21:39 完成全量导入和独立扫描，无数据库导入错误或 `analysisExceptions`，同样为 43 项告警 / 21 项 CVSS ≥ 7.0。完整 HTML/JSON/SARIF 见 [CI 扫描报告 artifact](https://github.com/BerryWang1996/netty-spring/actions/runs/39962150122/artifacts/12506331083)。

**覆盖限制**：Sonatype OSS Index 因未配置访问 token 而未运行。本次结果主要来自 NVD 数据，不能将 43 项视为整个依赖树全部已知漏洞的总数；GitHub Dependabot 补充识别的 Logback 6 项另列在本页，未因 Dependency-Check 未报告而忽略。

**复扫仍未通过，下面的适用性分析不等同于依赖漏洞已修复。** Spring 36 项中有 27 项对应组件未纳入本次运行依赖，另 9 项为依赖中实际存在的条件漏洞；Boot 7 项中有 3 项属于 Boot 本体真实风险，另 4 项的功能前提在本次 reactor 中不成立。本次没有添加 suppression，远端 CI 保留实际失败结果。当前内置入口不可达的漏洞，仍可能被下游应用新增的依赖、配置或 API 调用触发。按[依赖治理](dependency-governance.md)，高危漏洞处置属于企业安全发布的严格门槛；`1.10.1` 作为功能/稳定性补丁继续完成验证与发布，并如实记录扫描失败及剩余风险，不将其写成安全门禁通过或全部 CI 绿色。

### Spring Framework 5.3.39：组件不适用的 27 项

报告将 `spring-aop/beans/context/expression/jcl` 作为 `relatedDependencies` 合并显示在 `spring-core-5.3.39.jar` 下。SBOM 中 Spring Framework 只有这六个模块；不能仅看报告文件名就判定所有告警为 `spring-core` 误报。下表仅列实际缺失的组件；本项目的 `netty-spring-webmvc`、`netty-spring-websocket` 为自有实现，并非 Spring 对应模块。

| 官方公告（每个链接为一项 CVE） | 实际受影响组件或 API | 本次 reactor 适用性 |
|---|---|---|
| [CVE-2026-41855](https://spring.io/security/cve-2026-41855/) | JMS Jackson message converters | 无 `spring-jms` |
| [CVE-2026-41838](https://spring.io/security/cve-2026-41838/) | Spring WebSocket session ID | 无 `org.springframework:spring-websocket` |
| [CVE-2026-47888](https://spring.io/security/cve-2026-47888/) | `RSocketMessageHandler` | 无 `spring-messaging` 或 RSocket |
| [CVE-2026-47891](https://spring.io/security/cve-2026-47891/)、[CVE-2026-41845](https://spring.io/security/cve-2026-41845/)、[CVE-2026-59281](https://spring.io/security/cve-2026-59281/)、[CVE-2026-59314](https://spring.io/security/cve-2026-59314/) | JAXB XML decoder、`JavaScriptUtils`、`EscapedErrors`、`ContentDisposition` | 无 `spring-web`，未使用这些 API |
| [CVE-2026-41842](https://spring.io/security/cve-2026-41842/)、[CVE-2026-41841](https://spring.io/security/cve-2026-41841/)、[CVE-2026-41843](https://spring.io/security/cve-2026-41843/)、[CVE-2026-22745](https://spring.io/security/cve-2026-22745/)、[CVE-2026-22741](https://spring.io/security/cve-2026-22741/) | MVC/WebFlux 静态资源解析、版本资源和缓存 | 无 `spring-webmvc`、`spring-webflux` |
| [CVE-2026-22740](https://spring.io/security/cve-2026-22740/)、[CVE-2026-41840](https://spring.io/security/cve-2026-41840/)、[CVE-2026-41853](https://spring.io/security/cve-2026-41853/) | Spring multipart 请求读取与临时文件 | 无 Spring Web/WebFlux multipart 实现；本库使用 Netty 解析 |
| [CVE-2026-47884](https://spring.io/security/cve-2026-47884/)、[CVE-2026-41844](https://spring.io/security/cve-2026-41844/)、[CVE-2026-41846](https://spring.io/security/cve-2026-41846/)、[CVE-2026-47887](https://spring.io/security/cve-2026-47887/)、[CVE-2026-22737](https://spring.io/security/cve-2026-22737/) | XSLT/JSP/script view、自动视图名与 `UrlFileNameViewController` | 无 Spring MVC/WebFlux 视图实现 |
| [CVE-2026-47892](https://spring.io/security/cve-2026-47892/)、[CVE-2026-47893](https://spring.io/security/cve-2026-47893/)、[CVE-2026-41847](https://spring.io/security/cve-2026-41847/)、[CVE-2026-41839](https://spring.io/security/cve-2026-41839/) | WebFlux functional endpoint、WebSocket handshake、Kotlin Router DSL、session | 无 `spring-webflux` |
| [CVE-2026-59313](https://spring.io/security/cve-2026-59313/)、[CVE-2026-22735](https://spring.io/security/cve-2026-22735/) | MVC/WebFlux SSE | 无 Spring MVC/WebFlux SSE 实现 |
| [CVE-2026-59280](https://spring.io/security/cve-2026-59280/) | FreeMarker `SpringTemplateLoader` | 无 `spring-context-support` 或 FreeMarker |

这些是对**本次运行依赖**的组件误匹配结论；下游增加对应 Spring 模块后需重新评估。

### Spring Framework 5.3.39：仍存在的 9 项条件漏洞

下表分数来自本次扫描；官方公告的严重性标签可能不同，未据此降低门禁。

| CVE / 扫描 CVSS | 触发前提 | 本版代码证据与剩余风险 |
|---|---|---|
| [CVE-2026-41848](https://spring.io/security/cve-2026-41848/) / 7.5 | 攻击者控制传给 `AntPathMatcher` 的 **pattern** | `ServiceHandler.getMappingResolver()` 传入已注册的注解路由 key；`RequestMappingResolver` 的 `pathPattern` 来自构造参数 URL。请求只控制 path。下游动态注册不可信 pattern 时仍有风险 |
| [CVE-2026-41849](https://spring.io/security/cve-2026-41849/) / 7.5 | 评估不可信 SpEL，触发整数溢出 | 本库没有用户输入 SpEL 解析入口；`spring-expression` 依赖仍受影响 |
| [CVE-2026-41850](https://spring.io/security/cve-2026-41850/) / 7.5 | 评估不可信 SpEL，触发运算资源耗尽 | 同上；下游自行提供表达式功能时可触发 |
| [CVE-2026-41851](https://spring.io/security/cve-2026-41851/) / 7.5 | 评估并缓存不可信 SpEL | 本库无该解析或缓存入口；依赖缺陷未修复 |
| [CVE-2026-47886](https://spring.io/security/cve-2026-47886/) / 7.5 | 不可信 SpEL 对可访问的 BigDecimal/BigInteger 执行高次幂 | 本库无表达式入口；下游增加该入口时可触发 |
| [CVE-2026-59283](https://spring.io/security/cve-2026-59283/) / 9.1 | `SimpleEvaluationContext` 配合 IMMEDIATE/MIXED 表达式编译器 | 未创建该 context，未设置 `spring.expression.compiler.mode`；下游启用后安全限制可能被绕过 |
| [CVE-2026-59282](https://spring.io/security/cve-2026-59282/) / 7.5 | Spring 属性路径绑定用户输入，目标含自动扩容 List 及嵌套属性 | 无 `DataBinder`、`BeanWrapper`、`DirectFieldAccessor` 或 `AutoPopulatingList` 调用；HTTP 参数为自有绑定，JSON 使用 Jackson。下游自建绑定路径仍可能触发 |
| [CVE-2026-41852](https://spring.io/security/cve-2026-41852/) / 5.3 | 不可信 SpEL 绕过受限上下文调用无参方法 | 本库无用户输入 SpEL 解析入口；下游可触发 |
| [CVE-2024-38820](https://spring.io/security/cve-2024-38820/) / 5.3 | DataBinder `disallowedFields` 的大小写处理受 Locale 影响 | 本库未使用该字段黑名单绑定；下游使用时仍需处理 |

可复核位置：`ServiceHandler.java` 的 `getMappingResolver()`、`RequestMappingSupporter.java` 的 `getMappingUrls()` 和路由注册、`RequestMappingResolver.java` 的 `pathPattern` 初始化及 `resolveRequestBody()`；仓库源码/配置中无 SpEL 解析器、上述 Spring 属性绑定 API 或表达式编译开关。Boot 的 `@ConfigurationProperties` 用于应用配置，不是本库的 HTTP 参数绑定入口。

这些条件漏洞不能当作“对应组件不存在”的误报。官方列出的兼容修复涉及 Spring **5.3.41、5.3.49、5.3.50**，均仅企业支持渠道；当前 **5.3.39 未获得这些公开兼容补丁**。本版没有因此宣称整个依赖树安全，也未通过整体 Spring CPE suppression 隐藏告警。

### Spring Boot 2.7.18：7 项告警

| CVE / 扫描 CVSS | 分类及触发条件 | 本版判断 |
|---|---|---|
| [CVE-2026-40973](https://spring.io/security/cve-2026-40973/) / 7.0 | **本体真实风险**：`ApplicationTemp` 接受可预测且未验证所有权的目录；本地攻击者可结合持久化 Servlet session 利用 | 示例无 Servlet/persistent session 配置；下游开启后可触发，不能按缺模块误报抑制 |
| [CVE-2026-40975](https://spring.io/security/cve-2026-40975/) / 7.5 | **本体真实风险**：`${random.value}` 的 PRNG 不适合生成 secret | 仓库未使用该属性；下游把它用作 secret 时可触发。依赖缺陷仍保留 |
| [CVE-2026-40977](https://spring.io/security/cve-2026-40977/) / 6.7 | **本体真实风险**：`ApplicationPidFileWriter` 跟随可预测路径的符号链接 | 仓库未启用 PID writer；下游启用且攻击者可写 PID 目录时有风险 |
| [CVE-2026-40974](https://spring.io/security/cve-2026-40974/) / 9.8 | 功能条件不适用：Cassandra SSL 自动配置不验证主机名 | SBOM 无 Cassandra driver，未启用该功能 |
| [CVE-2026-40972](https://spring.io/security/cve-2026-40972/) / 7.5 | 功能条件不适用：DevTools remote secret 时间侧信道 | SBOM 无 DevTools，未启用远程开发功能 |
| [CVE-2026-22733](https://spring.io/security/cve-2026-22733/) / 8.1 | 功能条件不适用：Actuator、Spring Security 和受保护的 `/cloudfoundryapplication/*` 应用路径同时存在 | 当前仅有 Actuator，无 Spring Security 或该应用路径；消费者新增后需重新评估 |
| [CVE-2026-41001](https://spring.io/security/cve-2026-41001/) / 5.3 | 功能条件不适用：Artemis 默认临时数据目录可预测 | SBOM 无 Artemis，未启用嵌入式 broker |

官方 2.7 分支修复为 **2.7.32、2.7.33、2.7.34**，均仅企业支持渠道，不能从公开 Central 取得兼容补丁完成本次修复。**Boot 2.7.18 本体风险仍然存在；40973、40975 达到当前失败阈值，因此目前不能标记扫描门禁通过。** 完整基线迁移仍按 `2.0.0 → Boot 4.1` 计划执行；获得企业补丁是另一条需要单独验证的路径。

### Logback 1.2.13：Dependabot 补充的 6 项残余告警

根 POM 和 SBOM 仍包含 `logback-core/classic:1.2.13`、`slf4j-api:1.7.36`。仓库仅有测试用 ConsoleAppender 配置，没有网络日志接收器、Janino 依赖或生产自定义日志 XML；相关易受攻击类仍存在于 Logback 制品中，不能以默认未启用为由宣称已修复。下表严重度来自 GitHub Advisory/Dependabot，未与 NVD 的 43 项扫描计数混合。

| CVE / 严重度 | 触发条件与本项目现状 | 官方修复版本及本版处置 |
|---|---|---|
| [CVE-2026-10532](https://github.com/advisories/GHSA-jhq6-gfmj-v8fx) / 低 | 网络日志接收端反序列化攻击者数据，可绕过限制创建 Proxy；未启用 SimpleSocketServer / SimpleSSLSocketServer | 首修 1.5.34；依赖缺陷保留，不向不可信来源开放日志反序列化接收器 |
| [CVE-2026-9828](https://github.com/advisories/GHSA-p47f-322f-whfh) / 低 | 同类接收路径允许过宽的 java.lang / java.util 类型；未启用相关接收器 | 首修 1.5.33；保留风险记录，适用同上限制 |
| [CVE-2026-1225](https://github.com/advisories/GHSA-qqpg-mvqg-649v) / 低 | 攻击者可修改日志配置且 classpath 存在可利用类时，可能触发非预期实例化 | 首修 1.5.25；未提供不可信日志配置入口，部署端仍须保护配置及其来源 |
| [CVE-2025-11226](https://github.com/advisories/GHSA-25qh-j22f-pwp8) / 中 | 修改配置或控制启动环境，结合 Janino、Spring 执行条件表达式；当前有 Spring、无 Janino | 首修 1.3.16 / 1.5.19；当前缺少必要依赖，保留告警，不引入 Janino 条件配置 |
| [CVE-2024-12801](https://github.com/advisories/GHSA-6v67-2wr5-gvf4) / 低 | 攻击者修改日志 XML 的 DOCTYPE，可触发 SSRF | 首修 1.3.15 / 1.5.13；未提供不可信日志 XML 入口，部署端仍须保护配置写权限 |
| [CVE-2024-12798](https://github.com/advisories/GHSA-pr98-23f8-jwxv) / 中 | 修改配置或控制启动环境，通过 JaninoEventEvaluator 执行代码；当前无 Janino | 首修 1.3.15 / 1.5.13；当前缺少必要依赖，保留告警，不启用该 evaluator |

[Logback 官方发布记录](https://logback.qos.ch/news.html) 没有提供这些问题的 1.2.x 补丁。Logback 1.3 起要求 SLF4J 2，而 [Boot 2.7.18 的 LogbackLoggingSystem](https://github.com/spring-projects/spring-boot/blob/v2.7.18/spring-boot-project/spring-boot/src/main/java/org/springframework/boot/logging/logback/LogbackLoggingSystem.java) 仍直接使用旧绑定 `StaticLoggerBinder`；不将其作为 1.2 线兼容小补丁直接替换。日志基线随 `2.0.0 → Boot 4.1` 统一迁移，本版保留上述风险披露，未 suppression 或 dismiss。

### 已完成的兼容依赖升级

| 依赖 | 首次扫描基线 | 本版解析版本 | 验证结果 |
|---|---|---|---|
| Netty BOM | 4.1.134.Final | 4.1.139.Final | 全量回归及单机/双节点冒烟通过；原扫描告警不再出现 |
| Jackson BOM | 2.13.5 | 2.18.12 | 序列化与消息回归通过；原扫描告警不再出现 |
| SnakeYAML | 1.33 | 2.7 | 真实外部 YAML 配置启动通过；原扫描告警不再出现 |
| Log4j BOM | 2.17.2 | 2.25.5 | 日志桥接与应用启动通过；原扫描告警不再出现 |

新版 SBOM 已确认上述依赖版本一致，测试/冒烟、本地复扫及新版发布制品的 34 份签名验证均已完成。依赖升级没有修复保留在 Boot 2.7.18 / Spring 5.3.39 / Logback 1.2.13 中的本体及条件漏洞，也不构成“高危已清零”的结论。

## 升级与发布

`1.10.1` 已可从 Central 获取。Boot 2.7 应用从 `1.10.0` 升级时，将所用 `io.github.berrywang1996:netty-*` 依赖统一为 `1.10.1`，无需本库配置迁移；使用 `@AutowiredMessageSender` 的代码须改为构造注入，旧注解此前也不会自动注入字段。Boot 4.1 应用继续使用 `2.x`，不要混用两个主版本。

本次发布已完成 Portal 发布与公开下载核对。后续版本沿用[发布检查清单](release-checklist.md)：使用 `-P release` 排除 demo，`autoPublish=false` 时须明确完成 Portal 发布，再核对公开制品；不覆盖已发布版本。
