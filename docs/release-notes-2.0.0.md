# Release Notes — 2.0.0（开发中）

更新时间：2026-10-23

## 当前状态与范围

**当前源码版本为 `2.0.0-SNAPSHOT`，尚未发布，不是 GA。** 已完成依赖与源码适配、SBOM 和本地依赖扫描，单机及 Redis Pub/Sub 双节点、1.10.1 混部和完整应用回滚 smoke 通过。全量运行矩阵、其他传输的跨版本恢复与性能比较仍需完成；本页区分已测范围与剩余门禁，Maven Central 尚未提供 `2.0.0` 制品。

本页是本次实施结果、用户迁移差异和验证证据的唯一记录。执行顺序、剩余任务和验收门槛继续统一在[开发计划](development-plan.md)；发布操作沿用[发布清单](release-checklist.md)。本页不另建一套迁移计划。

本版以 [1.10.1](release-notes-1.10.1.md) 为功能和消息契约基线，目标为 Spring Boot 4.1 迁移，不新增集群协议或业务能力。`2.x` 不与 Boot 2/3 共用同一制品，也不承诺与 `1.x` 二进制兼容。以下迁移方式用于预发布验证，待本页补齐证据后才能作为对应部署模式的正式升级依据。

## 已锁定的运行基线

| 组件 | 当前版本或配置 | 管理方式 |
|---|---|---|
| Java | JDK 17 起；编译 `--release 17` | JDK 17/21/25 运行矩阵尚待验证 |
| Spring Boot | `4.1.2` | 根 POM 导入 Boot BOM |
| Spring Framework | `7.0.10` | Boot BOM |
| Netty | `4.2.19.Final` | Boot BOM，移除旧 4.1 BOM 覆盖 |
| Jackson | `3.1.8` | `tools.jackson:jackson-bom` 显式安全补丁覆盖；Boot 原管理版本为 3.1.7 |
| SnakeYAML | `2.7` | 保留安全覆盖，避免回退至 Boot 原管理的 2.6 |
| Lettuce | `7.5.2.RELEASE` | Boot BOM |
| SLF4J / Logback | `2.0.20` / `1.5.38` | Boot BOM，移除旧 SLF4J 1.x / Logback 1.2 覆盖 |
| Jakarta Activation | API `2.1.4`、Angus 实现 `2.0.3` | API 随 Boot；运行时实现显式管理 |
| Micrometer / jnats | `1.17.2` / `2.20.4` | Micrometer 随 Boot；可选 NATS 客户端显式管理 |
| JUnit Jupiter / Mockito / Testcontainers | `6.0.3` / `5.23.0` / `2.0.5` | Boot BOM；适配 Mockito 数组匹配和当前 Docker API |
| Lombok | `1.18.48` | 显式与 Boot 对齐，注解处理器使用同一版本 |
| Maven Compiler / Surefire | `3.15.0` / `3.5.6` | 显式管理，编译保留参数名 |

旧 Spring 5、Netty 4.1、Jackson 2、日志组件和 Testcontainers 的独立覆盖已清理，统一由 Boot 管理适用组件；Jackson 3.1.8 与 SnakeYAML 2.7 的必要覆盖保留并在 POM 说明原因。依赖升级本身不等于漏洞扫描通过；最终解析树、SBOM 和扫描结果见下方验证记录。`jnats` 仍为可选依赖，未因本次迁移转为默认必需组件。

## 已实施的框架适配

- 自动配置改用 `@AutoConfiguration`，通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册；公共模块四项、集群模块三项配置均已列入清单，旧 `spring.factories` 自动配置注册已移除。真实发现、顺序、用户 bean 覆盖和可选依赖隔离的 8 项新增测试通过；默认 registry 的实际指标绑定亦有断言。
- Health API 从 `org.springframework.boot.actuate.health.*` 迁到 `org.springframework.boot.health.contributor.*`，对应依赖为 `spring-boot-health`；Actuator/Micrometer 条件及自动配置顺序同步适配 Boot 4 的模块布局。自定义健康指示器也需调整 import 和依赖，实际端点输出需在应用中核对。[Boot 4 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- `ServiceHandlerUtil` 的 `javax.activation.MimetypesFileTypeMap` 改为 `jakarta.activation.MimetypesFileTypeMap`，并引入 Jakarta Activation API 与 Angus 运行时实现。`javax.crypto.*` 属于 JDK API，保持原名；不要批量替换所有 `javax.*`。
- Spring 7 不再从调试信息恢复 handler 参数名。业务 handler 必须使用 `javac -parameters`（Maven Compiler Plugin 配置 `<parameters>true</parameters>`）重新编译；仅 `-g` 不够。缺失元数据时初始化立即给出方法名与编译提示，避免请求到来后才静默丢失参数绑定；零参数方法不受影响。
- HTTP/WebSocket 服务及 mesh 的 NIO 线程组改用 `MultiThreadIoEventLoopGroup` 配合 `NioIoHandler.newFactory()`，保留 NIO transport 和现有生命周期职责。线程退出、连接关闭与资源回收的运行结果尚待记录。
- Netty 4.2 暴露出旧表单预解析会消费 JSON 正文的问题。现在仅对 `application/x-www-form-urlencoded` / `multipart/form-data` 启用表单 decoder，移除重复 `offer` 并恢复正文读取位置；保留 query/form 优先级、文件上传路径及原始正文。HTTP 绑定错误测试同时检查实际错误原因，避免把空正文误判为 JSON 类型校验成功。

Boot 4 的 Maven repackage 默认不再打入应用直接声明为 `optional` 的依赖。使用 NATS 的应用应将 `io.nats:jnats` 作为普通运行依赖显式加入；确需打入应用所有 optional 依赖时再有意配置 `includeOptional=true`。库自身的可选 NATS 边界保持不变，当前 Redis demo 无需该开关。应用若解析 Boot 默认日志，还应核对 ISO-8601 时间格式及日志字符集变化；本项目自有 `/netty/health`、`/netty/status` 不由 Actuator 新默认的探针分组替代。[Boot 4 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)

## Jackson 3：公开接口与 JSON 行为

Jackson Maven 坐标与 Java 包迁到 `tools.jackson`；`com.fasterxml.jackson.annotation` 中的注解继续保留原包。来自 `jackson-databind` 的注解（如 `JsonSerialize`、`JsonDeserialize`）属于迁移范围，应用不能仅因旧依赖仍能编译就保留其旧 import。mapper 构建后不可变，配置应在 builder 中完成。原 `JsonProcessingException` 对应 `tools.jackson.core.JacksonException`，后者是运行时异常，不再继承 `IOException`。[Jackson 官方迁移指南](https://github.com/FasterXML/jackson/blob/main/jackson3/MIGRATING_TO_JACKSON_3.md)

`JsonMessage` 的以下公开签名发生源码及二进制变化，调用方须重新编译：

| API | 1.10.1 的 mapper 类型 | 2.0.0 的 mapper 类型 |
|---|---|---|
| `JsonMessage(Object content, ObjectMapper objectMapper)` | `com.fasterxml.jackson.databind.ObjectMapper` | `tools.jackson.databind.ObjectMapper` |
| `ObjectMapper getObjectMapper()` | 同上 | 同上 |
| `void setObjectMapper(ObjectMapper objectMapper)` | 同上 | 同上 |

只使用 `new JsonMessage(content)` 的业务不需要新增 mapper 参数。传入 `null` 或调用 `setObjectMapper(null)` 仍恢复默认 mapper；setter 替换整个 mapper 引用，不修改已经构建的 mapper。AES-GCM codec 的可注入 mapper 构造器是包内测试入口，不属于公开扩展签名。

下面以“自定义 ISO 日期输出、允许空 bean”的现有用法说明迁移。`payload` 为业务对象，两段代码分别用于对应版本。

**1.10.1：**

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.github.berrywang1996.netty.spring.web.websocket.context.JsonMessage;

ObjectMapper mapper = new ObjectMapper();
mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
JsonMessage message = new JsonMessage(payload, mapper);
```

**2.0.0：**

```java
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import com.github.berrywang1996.netty.spring.web.websocket.context.JsonMessage;

ObjectMapper mapper = JsonMapper.builder()
        .configureForJackson2()
        .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
        .build();
JsonMessage message = new JsonMessage(payload, mapper);
```

应用若需要调整已有 mapper，使用 `mapper.rebuild()` 构建新实例后传给 `setObjectMapper`。应用直接调用 mapper 时，应复核原来只捕获 `IOException` 的逻辑；本库 `JsonMessage` 仍将序列化失败包装为 `IllegalStateException`，MVC JSON 渲染失败仍沿用空响应体回退，入站绑定失败仍走原错误处理路径。

本库手工创建的 mapper 使用 `JsonMapper.builder().configureForJackson2()` 固定旧绑定行为，MVC 两处另行关闭日期时间戳和空 bean 失败。该设置不会把底层 JsonFactory 全部退回旧默认值，Jackson 3 的流约束继续生效；不承诺所有异常、超深或复杂输入与旧版完全一致。

| 路径 | 保留的契约 | 当前证据 |
|---|---|---|
| HTTP JSON 输出 | `Date(0)` 输出 `1970-01-01T00:00:00.000+00:00`；枚举使用名称，空值保留，空 bean 为 `{}` | 固定样本测试通过 |
| HTTP / WebSocket 对象绑定 | 未知字段拒绝；枚举名称绑定；primitive 的 JSON null 沿用旧默认值；Jackson 注解继续生效 | 绑定测试通过，包括 JSON 解析失败原因断言 |
| `JsonMessage` | 默认 `Date` 为毫秒数、枚举使用名称；Map 顺序、空值与 JsonNode 内容保留；单发与共享 ByteBuf 路径一致 | 固定样本及 custom mapper 测试通过 |
| 跨节点 `J:` | 编码使用消息自己的 mapper，解码为 JsonNode，避免 JSON 字符串二次编码；标量、数组与 null 保留 JSON 类型 | 旧线格式、codec 测试及 Redis 混部双向 JSON smoke 通过 |
| AES-GCM | JSON 信封字段、AAD、IV 长度和明文消息字节保持现有约定 | 旧信封解密、固定 IV 输出和未知字段测试通过；真实加密文本/JSON 往返通过 |

固定样本由 `1.10.1` 使用的 Jackson `2.18.12` 实际生成，包括日期、枚举、空值、嵌套 Map、JsonNode 和 AES-GCM 信封。固定 IV 只用于测试；生产仍为每次加密生成随机 IV。这些样本测试不能代替真实跨版本节点混部验证。

## Netty 与 Lettuce 的运行差异

Netty 4.2 的默认分配器由 `pooled` 改为 `adaptive`，客户端 TLS 默认启用主机名校验。应用应核对证书 SAN、连接域名和信任链；校验失败时修复证书或连接配置，迁移方案不依赖关闭主机名验证。内存、GC 和延迟需按业务负载实测，不能从版本升级推导性能改善。[Netty 4.2 迁移指南](https://netty.io/wiki/netty-4.2-migration-guide.html)

`DefaultMessageSender` 的共享广播缓冲区仍显式使用 `PooledByteBufAllocator.DEFAULT`。`netty.allocator.used.heap/direct` 指标保留名称及 bytes 单位，改为汇总默认 allocator 与该共享 pooled 实例；两者相同时只计一次。某一维用量未知时返回 `NaN`，避免把部分用量当作总量。该指标是这两个全局实例的用量，不代表应用自建 allocator 或 JVM 的全部内存；运行负载下的内存与延迟仍需实测。

Lettuce 7 默认开启 Redis Cluster 自适应拓扑刷新。本项目自动创建的 `RedisClusterClient` 已显式调用 `disableAllAdaptiveRefreshTriggers()`，并保持定期刷新关闭，保留 `1.10.1` 的拓扑固定策略，避免升级时自动改用容器或 NAT 后不可达的节点公告地址。该配置不是对所有 Redis 部署的通用建议；应用自定义 `RedisClusterClient` 时仍需自行配置可达地址、拓扑刷新及故障恢复策略，并进行演练。[Lettuce 7.0 发布说明](https://github.com/redis/lettuce/releases/tag/7.0.0.RELEASE)

## 用户升级与完整应用回滚

以下是针对当前已识别差异的预发布操作说明。项目实施任务和完成条件只维护在[开发计划](development-plan.md)；正式支持范围以本页最终记录的实测结果为准。

1. 保存可启动的完整 `1.10.1` 应用制品或镜像、依赖锁定信息、配置和所用中间件数据备份，记录自定义 mapper、codec、SPI、TLS、Redis/NATS 模式与可靠投递设置。先确认旧应用能够重新部署。
2. 在隔离环境同步升级应用的 Boot/BOM、netty-spring 和相关第三方 starter。清理应用自身的 Spring 5、Netty 4.1、旧日志绑定及 Jackson 2 覆盖，检查完整依赖树；调整 Jakarta、health 和 mapper API 后重新编译、打包整个应用。不能仅替换一个 netty-spring jar。
3. 使用实际配置验证 HTTP、WebSocket、JSON/加密消息、健康与指标，再验证所用 broker/registry、跨节点投递、离线数据、可靠重放和中间件重连。核对 TLS 证书及内存指标，保存结果和配置组合。
4. 仅对已经实际完成 `1.10.1` / `2.0.0` 混部演练的部署模式安排滚动切换。单机、Redis standalone、Redis Cluster、NATS/JetStream、mesh 的结果不能相互替代；尚无证据的模式按整体切换或维护窗口设计，当前不承诺零停机升级。
5. 回滚时停止新版本接流量，恢复完整旧应用镜像或制品、依赖和对应配置，核对旧版能否读取升级期间写入的数据及可靠投递状态，再恢复流量。若数据不兼容，按已演练的数据恢复方案处理并说明可能丢失的更新；不要只降一个 jar，也不要以清空共享数据代替回滚。重放仍按既有 at-least-once 语义处理重复。

## 验证记录

| 检查 | 当前结果 |
|---|---|
| 源码版本 | `2.0.0-SNAPSHOT`；开发分支 `codex/2.0.0-spring-boot-4.1` |
| Reactor 编译 / 打包 | 11 个模块编译、打包成功，demo fat jar 已用于真实运行验证 |
| JSON 契约测试 | 新增 17 项全部通过：JsonMessage 4、MVC 输出 3、HTTP 绑定 3、跨节点 codec 3、crypto 3、WebSocket 未知字段 1 |
| 全量 `verify` | JDK 17.0.11 / Maven 3.9.16：11 模块成功，119 份 Surefire 报告，689 tests / 0 failures / 0 errors / 0 skipped；2026-10-23 22:38 CST 完成 |
| 自动配置发现 / 可选依赖 / MIME | 8 项新增真实发现测试、2 项 MIME 测试通过；另有 3 项真实 javac 参数元数据、4 项请求体解析和 4 项 allocator 指标回归 |
| 依赖一致性 / SBOM | CycloneDX JSON/XML 已生成；根版本 `2.0.0-SNAPSHOT`、64 个组件，同坐标无多版本；未见 Spring 5、Netty 4.1 或 Jackson 2 core/databind 残留，详见下方证据 |
| 漏洞扫描 | Dependency-Check `12.2.2` 本地扫描退出 0，CVSS 7.0 门禁通过；38 条聚合依赖记录、0 条漏洞告警、0 条抑制、无 analysisExceptions；OSS Index 未启用，覆盖限制见下方 |
| JDK 17 / 21 / 25 与远端 CI | 本地 JDK 17 全量通过；首轮远端 JDK 21/25 通过，JDK 17 捕获出站帧测试竞态，已修正并定向 21 tests 全通过；最终矩阵待重跑 |
| 单机、双节点与真实中间件 | 单机 smoke 16 项通过；2.0.0 双节点 Redis Pub/Sub 双向 JSON 广播通过；集群模块 336 项真实中间件/单测全部通过，无跳过 |
| 1.10.1 混部及完整应用回滚 | 旧/新各一节点 → 双新节点 → 完整 1.10.1 双节点镜像，三阶段双向 JSON 广播通过，回滚保留 Redis 容器；覆盖边界见下方 |
| 内存 / 延迟 / 资源回收 | allocator 指标已适配并新增汇总、去重和未知值测试；负载比较与运行证据待补 |
| GA 标签 / Maven Central | 未执行，未发布 |

### 部署演练范围（2026-10-23）

单机使用 JRE `17.0.20.1` 的隔离容器，以真实 YAML 将 Netty 端口设为 `8082`。普通模式 7 项检查覆盖 GET/POST、JSON/cookie、WebSocket 文本/JSON、HTTP 触发广播、health/status；`crypto-demo,auth-demo` 组合的 9 项检查覆盖缺少 token 的两个 403，以及 query token 加密文本和 Bearer token 加密 JSON 往返。两阶段均核对 fat jar 的 Maven 版本、嵌套 core/WebSocket/Boot 版本及 SHA-256 `ca8402cd86722efd2171eb1ee5bf496d7d6167ee0661a61bd34c7cfca117d597`，测试结束已清理自建环境。

混部演练复用 `docker-demo/` 的 nginx + Redis 7 和同一集群配置；每个阶段入口验证节点实际 jar 版本，`smoke.js` 验证两个不同节点之间双向 JSON 广播及 `originNode`。顺序为 `1.10.1 / 2.0.0-SNAPSHOT`、`2.0.0-SNAPSHOT / 2.0.0-SNAPSHOT`、恢复 `1.10.1 / 1.10.1`；恢复完整应用镜像，未替换单个库 jar，也未通过清空 Redis 规避兼容问题。切换后刷新 LB 并重新建立测试连接，因此**不证明已有连接无中断或零停机**。

该演练只证明上述 Redis Pub/Sub 基础消息路径；尚未验证跨版本可靠重放、离线数据读取、HMAC、Redis Cluster、NATS/JetStream 或 mesh 混部，不能把同版本集成测试的通过结果扩展为这些混部保证。证据保存在忽略目录 `.m2/smoke-200-*`、`.m2/compat-*-200-*`，正式发布前继续按开发计划补齐所承诺的范围。

### 依赖与扫描证据（2026-10-23）

本轮 SBOM 生成于 `2026-10-23T14:32:35Z`，包含 64 个组件（9 个项目子模块、55 个外部组件）。已核对 Spring 的 5 个组件统一为 `7.0.10`、Boot 的 12 个组件统一为 `4.1.2`、Netty 的 11 个组件统一为 `4.2.19.Final`；Jackson core/databind 均为 `tools.jackson.core:3.1.8`，Lettuce 为 `7.5.2.RELEASE`、SnakeYAML 为 `2.7`、SLF4J 为 `2.0.20`、Logback 为 `1.5.38`。SBOM 未发现同一 Maven 坐标同时出现多个版本，也未发现 Spring 5、Netty 4.1、旧 `com.fasterxml.jackson.core:jackson-core/jackson-databind` 或 Jackson 2 datatype/module/dataformat 组件。仍存在的 `com.fasterxml.jackson.core:jackson-annotations:2.21` 是 Jackson 3 沿用的注解库，不是旧 databind 残留。

Dependency-Check 报告时间为 `2026-10-23T14:32:42Z`。38 条顶层依赖记录中有 7 条 reactor SNAPSHOT 虚拟依赖，另有 27 条归并在相关依赖中的记录；这与 SBOM 的组件计数口径不同。报告及相关依赖中没有漏洞条目或 `analysisExceptions`，抑制文件仍为 0 条规则；日志显示 NVD CVE、Known Exploited Vulnerability 等分析器执行完成，构建成功，`failBuildOnCVSS=7.0` 和 `failOnError=true` 保持不变。

NVD 使用 [Dependency-Check 官方镜像](https://dependency-check.github.io/DependencyCheck/data/mirrornvd.html)。扫描前于 `2026-10-23T14:19:23Z` 独立读取 `cache.properties`，其 `lastModifiedDate=2026-10-22T15:44:59Z`，年龄约 22.57 小时，满足不超过 72 小时且不超前 5 分钟的 CI 规则。本次扫描复用本地数据库：报告的最近检查时间为 `2026-10-23T13:22:12Z`，数据修改时间与镜像一致；日志因检查间隔未到而复用 NVD 和 CISA KEV 缓存，没有声称重新全量导入。

**覆盖限制：** Sonatype OSS Index 因未配置凭据而禁用，未取得该来源的本轮结果；test scope 按配置排除。此结果是当前依赖与已有漏洞数据的匹配结论，不是源代码安全审计，也不证明所有潜在漏洞、应用额外依赖或所有 CI 均已通过。本轮可确认的是上述 Dependency-Check 门禁通过，不能据此表述为“全库安全无漏洞”。

本地证据已复制到忽略目录 `.m2/scan-2.0.0/`，包括扫描 JSON/HTML/SARIF、日志、SBOM JSON/XML、镜像输入和汇总，避免后续 `clean` 删除。扫描 JSON 的 SHA-256 为 `a5ef9c45bfdf3b16b88303617be69dc082f8bf612eac078d8537fa11d10fd3f5`；SBOM JSON 的 SHA-256 为 `8c54405723374da1d4ce7a8b94922505561e77398ad3b86cf278bb899c6dedd2`。远端同版本扫描与制品留档结果仍待单独记录。

后续证据直接更新本页，不新增平行迁移报告。确认支持的模式须写明版本、配置、实际运行结果和未覆盖项；出现失败或跳过时保留事实，按[开发计划](development-plan.md)推进剩余验收。
