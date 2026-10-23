# Release Notes — 2.0.0（开发中）

更新时间：2026-10-23

## 当前状态与范围

**当前源码版本为 `2.0.0-SNAPSHOT`，尚未发布，不是 GA。** 已完成依赖与源码适配、JDK 17/21/25 全量运行矩阵、SBOM 和本地/远端依赖扫描；单机及 Redis Pub/Sub 双节点、1.10.1 HMAC 混部、节点重连和完整应用回滚 smoke 通过。本轮修复状态计数重复汇总，并补齐独立版本 JVM 的 Redis standalone 持久化、Redis Cluster 三主节点、NATS/JetStream 和 mesh 传输兼容演练。同机短时负载比较已有记录，其他未覆盖部署组合、持续及目标规模负载仍待验收；本页区分已测范围与剩余门禁，Maven Central 尚未提供 `2.0.0` 制品。

本页是本次实施结果、用户迁移差异和验证证据的唯一记录。执行顺序、剩余任务和验收门槛继续统一在[开发计划](development-plan.md)；发布操作沿用[发布清单](release-checklist.md)。本页不另建一套迁移计划。

本版以 [1.10.1](release-notes-1.10.1.md) 为功能和消息契约基线，目标为 Spring Boot 4.1 迁移，不新增集群协议或业务能力。`2.x` 不与 Boot 2/3 共用同一制品，也不承诺与 `1.x` 二进制兼容。以下迁移方式用于预发布验证，待本页补齐证据后才能作为对应部署模式的正式升级依据。

## 已锁定的运行基线

| 组件 | 当前版本或配置 | 管理方式 |
|---|---|---|
| Java | JDK 17 起；编译 `--release 17` | JDK 17/21/25 远端运行矩阵通过 |
| Spring Boot | `4.1.2` | 根 POM 导入 Boot BOM |
| Spring Framework | `7.0.10` | Boot BOM |
| Netty | `4.2.19.Final` | Boot BOM，移除旧 4.1 BOM 覆盖 |
| Jackson | `3.1.8` | `tools.jackson:jackson-bom` 显式安全补丁覆盖；Boot 原管理版本为 3.1.7 |
| SnakeYAML | `2.7` | 保留安全覆盖，避免回退至 Boot 原管理的 2.6 |
| Lettuce | `7.5.2.RELEASE` | Boot BOM |
| SLF4J / Logback | `2.0.20` / `1.6.5` | SLF4J 随 Boot；Logback core/classic 成对安全覆盖 Boot 原管理的 1.5.38 |
| Jakarta Activation | API `2.1.4`、Angus 实现 `2.0.3` | API 随 Boot；运行时实现显式管理 |
| Micrometer / jnats | `1.17.2` / `2.20.4` | Micrometer 随 Boot；可选 NATS 客户端显式管理 |
| JUnit Jupiter / Mockito / Testcontainers | `6.0.3` / `5.23.0` / `2.0.5` | Boot BOM；适配 Mockito 数组匹配和当前 Docker API |
| Lombok | `1.18.48` | 显式与 Boot 对齐，注解处理器使用同一版本 |
| Maven Compiler / Surefire | `3.15.0` / `3.5.6` | 显式管理，编译保留参数名 |

旧 Spring 5、Netty 4.1、Jackson 2、日志组件和 Testcontainers 的独立覆盖已清理，统一由 Boot 管理适用组件；Jackson 3.1.8、SnakeYAML 2.7 和 Logback core/classic 1.6.5 的必要安全覆盖在 POM 注明原因。依赖升级本身不等于漏洞扫描通过；最终解析树、SBOM 和扫描结果见下方验证记录。`jnats` 仍为可选依赖，未因本次迁移转为默认必需组件。

这些版本是本仓库 reactor 与 demo 的解析结果。应用自己的 Boot parent/BOM 可能优先管理传递依赖；接入 starter 时，应在应用的 `dependencyManagement` 中保留上述 Jackson、SnakeYAML 和成对 Logback 安全覆盖，再检查应用最终依赖树与 fat jar，不能仅凭库的 POM 推断最终版本。

## 已实施的框架适配

- 自动配置改用 `@AutoConfiguration`，通过 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册；公共模块四项、集群模块三项配置均已列入清单，旧 `spring.factories` 自动配置注册已移除。真实发现、顺序、用户 bean 覆盖和可选依赖隔离的 8 项新增测试通过；默认 registry 的实际指标绑定亦有断言。
- Health API 从 `org.springframework.boot.actuate.health.*` 迁到 `org.springframework.boot.health.contributor.*`，对应依赖为 `spring-boot-health`；Actuator/Micrometer 条件及自动配置顺序同步适配 Boot 4 的模块布局。自定义健康指示器也需调整 import 和依赖，实际端点输出需在应用中核对。[Boot 4 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)
- `ServiceHandlerUtil` 的 `javax.activation.MimetypesFileTypeMap` 改为 `jakarta.activation.MimetypesFileTypeMap`，并引入 Jakarta Activation API 与 Angus 运行时实现。`javax.crypto.*` 属于 JDK API，保持原名；不要批量替换所有 `javax.*`。
- Spring 7 不再从调试信息恢复 handler 参数名。业务 handler 必须使用 `javac -parameters`（Maven Compiler Plugin 配置 `<parameters>true</parameters>`）重新编译；仅 `-g` 不够。缺失元数据时初始化立即给出方法名与编译提示，避免请求到来后才静默丢失参数绑定；零参数方法不受影响。
- HTTP/WebSocket 服务及 mesh 的 NIO 线程组改用 `MultiThreadIoEventLoopGroup` 配合 `NioIoHandler.newFactory()`，保留 NIO transport 和现有生命周期职责。既有线程退出、连接关闭与资源回收回归通过；持续负载下的资源比较尚待完成。
- Netty 4.2 暴露出旧表单预解析会消费 JSON 正文的问题。现在仅对 `application/x-www-form-urlencoded` / `multipart/form-data` 启用表单 decoder，移除重复 `offer` 并恢复正文读取位置；保留 query/form 优先级、文件上传路径及原始正文。HTTP 绑定错误测试同时检查实际错误原因，避免把空正文误判为 JSON 类型校验成功。

Boot 4 的 Maven repackage 默认不再打入应用直接声明为 `optional` 的依赖。使用 NATS 的应用应将 `io.nats:jnats` 作为普通运行依赖显式加入；确需打入应用所有 optional 依赖时再有意配置 `includeOptional=true`。库自身的可选 NATS 边界保持不变，当前 Redis demo 无需该开关。应用若解析 Boot 默认日志，还应核对 ISO-8601 时间格式及日志字符集变化；本项目自有 `/netty/health`、`/netty/status` 不由 Actuator 新默认的探针分组替代。[Boot 4 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)

Logback 1.6.5 加强 `MDCBasedDiscriminator` 的值校验：路径分隔符、`..`、变量表达式、空值或超长值会回退到配置的 `defaultValue`。应用若以含 `/` 的 `netty.uri` 直接作为 `SiftingAppender` 的日志分组值，升级后这些日志会合流到默认文件；应改用经过校验的分组标识，并检查默认文件的滚动策略。Java 17 独立探针已验证普通日志、MDC 清理、实际滚动和非法值回退；没有越界落盘。修复依据和扫描边界见[依赖扫描证据](#dependency-scan-evidence)。

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
| 本地全量 `clean verify` | JDK 17.0.11：11 模块成功，120 份 Surefire 报告，694 tests / 0 failures / 0 errors / 0 skipped；2026-10-23 23:39 CST 完成，包含 Logback 1.6.5 和状态计数去重修复。日志与汇总保存在 `.m2/verification-200-status-fix/`；远端既有 689 项结果仍按对应提交单列 |
| 自动配置发现 / 可选依赖 / MIME | 8 项新增真实发现测试、2 项 MIME 测试通过；另有 3 项真实 javac 参数元数据、4 项请求体解析和 4 项 allocator 指标回归 |
| 依赖一致性 / SBOM | CycloneDX JSON/XML 已生成；根版本 `2.0.0-SNAPSHOT`、64 个组件，同坐标无多版本；未见 Spring 5、Netty 4.1 或 Jackson 2 core/databind 残留，详见下方证据 |
| 漏洞扫描 | Logback core/classic 成对升级 `1.6.5` 后，clean 构建及 Dependency-Check `12.2.2` 复扫成功，CVSS 7.0 门禁通过；38 条聚合依赖记录、工具报告 0 条漏洞告警、0 条抑制、无 analysisExceptions；人工补充的 2 条 Logback 中危已通过该版本修复，OSS Index 未启用，详见[扫描证据与覆盖限制](#dependency-scan-evidence) |
| JDK 17 / 21 / 25 与远端 CI | 源码与依赖提交 `2c2d61a` 的[完整 CI](https://github.com/BerryWang1996/netty-spring/actions/runs/39974771937)通过：每个 JDK 均 119 份 XML、689 tests / 0 failures / 0 errors / 0 skipped，共 2,067 次测试；SBOM、Dependency Scan 均成功。[双节点 Docker smoke](https://github.com/BerryWang1996/netty-spring/actions/runs/39974779070)双向投递通过 |
| 单机、双节点与真实中间件 | 单机普通/crypto/auth 16 项及 TLS/gzip/分片 8 项通过；2.0.0 双节点 Redis Pub/Sub 双向 JSON 广播通过；集群模块 336 项真实中间件/单测全部通过，无跳过 |
| 1.10.1 混部及完整应用回滚 | 基础三阶段及最终依赖上的 6 阶段 HMAC strict 演练通过：旧新双向、重建新节点后重连、错密钥拒绝/恢复、双新、完整双旧回滚；Redis 容器与验证数据保留，覆盖边界见下方 |
| Redis standalone / Cluster / NATS / JetStream / mesh 跨版本 | 最终验收制品的 Redis 持久化 9 阶段、Redis Cluster 7 阶段、传输 70 组全部通过；双版本 JVM、实际中间件、积压顺序/ACK/回滚、协议及清理证据见下方，不能扩大为所有部署组合已验收 |
| WebSocket 状态统计 | 共享 recorder 按身份去重，新增 5 项回归；真实 8 连接的握手、收发和关闭计数精确匹配，received 包含控制帧的口径不变 |
| 内存 / 延迟 / 资源回收 | allocator 指标已适配并新增汇总、去重和未知值测试；同机 64 连接、旧新各 3 轮短时比较已记录，客户端序号核对无丢失/重复；持续/目标规模负载及泄漏结论仍待验证 |
| Sources / Javadoc / 签名 | 本地 SNAPSHOT release profile 严格复验通过，34 份制品的签名均通过指定 fingerprint 的 `VALIDSIG` 校验；6 个含源码模块的 sources 与实际 Java 文件数一致，Javadoc 均有 HTML。最终 GA 版本仍须重新构建、签名和上传 |
| GA 标签 / Maven Central | 未执行，未发布 |

首次 release profile 检查触发跨模块 Javadoc offline link 探测，对无 Java 源的 starter 递归调用 Maven 时出现 JVM 初始化错误；旧配置 `failOnError=false` 随后忽略链接错误并输出 `BUILD SUCCESS`。现已设为 `failOnError=true`，并按 [Maven Javadoc 官方说明](https://maven.apache.org/plugins/maven-javadoc-plugin/examples/links-configuration.html) 用 `detectOfflineLinks=false` 关闭模块间自动离线链接，避免独立 Central 文档指向未发布的 reactor 路径。2026-10-23 23:51 CST 严格复验成功、日志无 `[ERROR]`，34 份制品及 34 份签名、清单和日志归档于 `.m2/release-artifacts-200-snapshot/`。6 个含源码模块的 Java 文件数分别为 26/30/26/69/8/5，sources 内容逐模块匹配，Javadoc HTML 数分别为 102/85/85/207/25/19；3 个依赖聚合 starter 无 Java 源，因此不生成 sources、Javadoc 为空包，沿用 1.10 已发布规则。`doclint=none` 仍保留，因此此次严格复验不等于所有注释 lint 或 warning 清零，也不替代最终 GA 制品的重新签名、上传和 Central 验证。

### 部署演练范围（2026-10-23）

单机使用 JRE `17.0.20.1` 的隔离容器，以真实 YAML 将 Netty 端口设为 `8082`。普通模式 7 项检查覆盖 GET/POST、JSON/cookie、WebSocket 文本/JSON、HTTP 触发广播、health/status；`crypto-demo,auth-demo` 组合的 9 项检查覆盖缺少 token 的两个 403，以及 query token 加密文本和 Bearer token 加密 JSON 往返。两阶段在 Logback 1.6.5 clean 重建后重新通过，入口核对 fat jar 的 Maven 版本、嵌套 core/WebSocket/Boot/Logback 版本及 SHA-256 `4524112d295d0221d7bee80340bcecbfd61f63f96ec1d9dd8176619e0cf15aea`，测试结束已清理自建环境。

同一制品另完成 8 项 TLS/gzip/分片检查：临时 CA 签发含 `localhost` / `127.0.0.1` SAN 的测试证书，HTTPS 的 TLS 1.2/1.3 与 WSS 均保留信任链和主机名校验；HTTPS/WSS 错误主机名都被拒绝。gzip 将 12,461 字节响应压至 3,846 字节，解压逐字节一致。显式设 `max-frame-aggregation-buffer-size=8192` 后，跨 UTF-8 字符边界的三段 TEXT/CONTINUATION 帧中途无回包，最终恰好一条完整回显；该功能默认仍关闭。容器、网络、监听及临时私钥已清理，证据为 `.m2/smoke-200-tls-*`。

混部演练复用 `docker-demo/` 的 nginx + Redis 7 和同一集群配置；每个阶段入口验证节点实际 jar 版本，`smoke.js` 验证两个不同节点之间双向 JSON 广播及 `originNode`。顺序为 `1.10.1 / 2.0.0-SNAPSHOT`、`2.0.0-SNAPSHOT / 2.0.0-SNAPSHOT`、恢复 `1.10.1 / 1.10.1`；恢复完整应用镜像，未替换单个库 jar，也未通过清空 Redis 规避兼容问题。切换后刷新 LB 并重新建立测试连接，因此**不证明已有连接无中断或零停机**。

最终 Logback 1.6.5 制品另完成 6 阶段 HMAC strict 演练：旧新双向 JSON、重建新节点并保留旧节点已有连接/重连新节点、双方密钥不匹配时拒绝跨节点消息、恢复密钥、双新节点和完整双旧回滚。共 10 次正向投递、2 次负向拒绝；负向场景本地回显正常，两端日志明确拒绝 invalid HMAC。全程验证同一 Redis 容器和测试 sentinel 数据保留。新节点原连接在重建时中断，不将重连成功描述为零停机。证据为 `.m2/mixed-advanced-200/2026-10-23T15-09-00-135Z/`，自建容器与网络已清理。

以上应用 smoke 覆盖 Redis Pub/Sub 及其 HMAC 路径，demo 调用普通 `broadcastJson`。可靠投递、离线存储及其他传输由下面的独立 harness 实际调用，不从配置启用推导覆盖。基础证据保存在忽略目录 `.m2/smoke-200-*`、`.m2/compat-*-200-*`。

**状态统计修复：** 已修复 `1.10.1` 中原有的 `/netty/status` 共享 event recorder 重复汇总问题：同一 recorder 只计一次，独立 recorder 和自定义普通 Map 继续累加，会话数仍逐 URI 汇总。新增 5 项回归覆盖共享 3 URI、独立/空 recorder、自定义计数和实例身份；已有控制帧测试明确 Ping/Pong/Close 各增加一次 received。`messagesReceived` 仍统计通过会话/大小检查的入站帧，包含控制帧，不能等同业务消息成功数。

在最终验收制品上，以真实 8 个连接、3 个 WebSocket URI 映射完成精确状态断言：握手尝试/成功和关闭各为 8；预热发送 6,110、计量发送 29,109，全部回显；`messagesSent=35,219`、`messagesReceived=35,227`，差值为 8 个 Close 帧。会话归零，队列清空，无拒绝或写失败。证据位于 `.m2/load-acceptance-2026-10-23T15-41-52-801Z/`；这轮 2 秒计量用于验证统计修复，不作为容量或持续负载结论。

### 持久化与传输跨版本演练（2026-10-23）

各 harness 分别从完整应用镜像解出依赖，在各自独立 classpath 上编译并运行，不混放旧新 jar。旧版为 `1.10.1`，fat jar SHA-256 `a9d5b382b9f86da3905222b8bec43c827dcbe4bab9d5a7af023f5de4fd530d09`；本轮新版包含状态去重修复，验收镜像 `netty-spring-200-acceptance:2.0.0-SNAPSHOT` 的 image ID 为 `sha256:3263b7dfbe2d60ba45aae8c6385f9315d57deafdcd70a6c0c67ca7015a9b9293`，fat jar SHA-256 为 `f5e12e04b753b5abd1df634845329d1c2ba4f5b32e2e841c8bf87a2b2be417d9`。运行入口核对实际版本，镜像与制品哈希固定；源码、命令、原始事件及制品来源随证据保存。它们与上文较早的应用 smoke 制品分别记录，不混用哈希或远端测试数。

| 演练 | 已验证范围与结果 | 证据目录 |
|---|---|---|
| Redis 持久化，9/9 阶段 | 真实 Redis `7.4.11` standalone、strict HMAC；旧→新及新→旧的可靠积压按序重放、ACK 后重启不重复；回调阻塞时确认 pending=1，强杀并跨版本重启后重投及 ACK 清零；`sendToUser` 离线写入、读取/投递边界、锁排他/到期恢复、确认后删除、key TTL 与条目年龄淘汰；恢复完整旧版依赖 classpath 后读取新版数据，同一 Redis 容器与 sentinel 保留 | `.m2/state-compat-200-2026-10-23T15-43-59-975Z/` |
| Redis Cluster，7/7 阶段 | 真实 Redis 三主节点、16,384 slots；两版实际配置类创建 Cluster registry/heartbeat/reaper/Pub/Sub；24 条注册数据跨三主节点双向读取/删除，心跳实际到期/续约、reaper 排他/租约到期、strict HMAC JSON 广播/单播、双新后完整双旧 classpath 回滚均通过。room/reliable/offline/presence 显式启用时仍无对应 6 个 bean，4 个公开 API 均拒绝；保留同一 Redis 集群及旧新写入数据 | `.m2/redis-cluster-compat-200-2026-10-23T15-52-53-034Z/` |
| NATS / JetStream / mesh，70/70 组 | 真实 NATS `2.10.29`（JetStream）与 Redis `7.4.11` mesh directory；旧旧、旧新、回滚旧旧的文本/JSON/二进制双向传输；Core/mesh 广播和单播、JetStream 广播；节点替换后的 durable backlog 按发布顺序重放并核对 ACK，旧版读取新版创建的 stream；NATS 重启后两版 DEGRADED→ACTIVE 和已有订阅恢复；Core/mesh 离线单播不重放 | `.m2/transport-compat-200-2026-10-23T15-45-00-607Z/` |

协议检查确认 v2 envelope 的 9 字段、`H1:` HMAC-SHA256 包装、JSON 无二次编码，以及 mesh 的 4 字节大端长度前缀；拆包、粘包正向投递和错误 HMAC、未来版本 `99`、超过 8,192 字节上限的负向检查均实际执行。未来版本负向用例在同一 TCP 流后发送有效帧并等待其到达，再检查拒绝结果；可靠重放检查实际事件顺序及 ACK 状态。两套脚本将异常 worker 退出或清理/清单查询失败计为失败；本轮自建容器和网络已清理，传输演练的 6 个端口均可重新绑定。

**覆盖边界：** Redis standalone harness 调用公开持久化 API，`LocalSender` 记录本地投递边界，未验证真实浏览器上线触发回填；其 TTL 结论限于离线 key、条目年龄和 drain lock。Redis Cluster harness 注册实际版本的配置类并绑定属性，未运行 HTTP/WebSocket 服务；三主节点无副本，未测试 failover、resharding 或浏览器连接。JetStream 本轮覆盖尚未消费的 durable backlog，未覆盖回调处理中强杀后的未 ACK 重投。Mesh 本轮验证传输及 framing，未覆盖跨版本 interest/room/presence；all-NATS KV 自动装配亦未覆盖。这些 harness 不替代完整应用部署、持续容量或生产 SLI 验收，阶段 5 整体门禁保持未完成。

### 同机短时负载比较（2026-10-23）

按旧/新交替执行 6 轮，每轮启动独立容器，64 个持久 WebSocket 连接访问 `/ws/test`，每连接最多 1 条在途消息。消息为 256 字节、包含轮次/连接/序号的文本；预热 5 秒、计量 10 秒，再收完在途回显。两版均使用 Temurin `17.0.20.1+1`、2 CPU 配额、768 MiB 容器内存且禁 swap、G1、固定 256 MiB heap 与 256 MiB direct 上限；显式线程配置及日志 OFF 一致，allocator 保留各版本默认值。

| 指标 | 1.10.1 | 2.0.0-SNAPSHOT |
|---|---:|---:|
| ACK/s，中位数 [最小, 最大] | 36,224.7 [35,773.6, 36,530.8] | 36,086.3 [36,057.7, 36,119.3] |
| 每轮 p50 中位数，ms | 1.677 | 1.674 |
| 每轮 p95 中位数，ms | 2.551 | 2.615 |
| 每轮 p99 中位数，ms | 3.382 | 3.470 |
| 每轮 CPU 均值范围，100% 为一核 | 168.35–170.18% | 173.76–174.84% |
| 每轮进程 VmRSS 均值范围，MiB | 273.61–276.87 | 263.21–268.29 |
| 进程原生线程数 | 30 | 32 |

本组新版吞吐中位数为旧版的 99.62%，p95/p99 分别增加约 2.50%/2.60%；未见明显端到端退化，但不足以判断服务端容量变化。旧版计量阶段发送/最终确认均为 1,085,483，新版均为 1,082,825；各有 192 条尾部回显在计量窗口结束后收齐。吞吐只计 10 秒窗口内 ACK，延迟包含这些尾部消息；两版均无丢失、重复、损坏或连接掉线。已重新读取逐条 ACK 二进制记录并核对序号、数量和分位数，不使用 `/netty/status` 计数。

负载客户端在 Windows，经 Docker Desktop 网络访问；客户端约占用单核的 95%，可能限制吞吐，同机用户 `mysql-8` 容器仍运行。每版仅 3 次短样本，不能替代持续负载、目标连接规模或内存泄漏验证，也不作为生产 SLI。Docker stats 的 cgroup 内存与 `/proc/1/status` 的 VmRSS 分开记录，未混称 RSS；原始采样、镜像/JVM 身份、配置、消息记录、复算结果和清理证据位于 `.m2/perf-200-results-2026-10-23T15-13-48-986Z/`，自建容器和网络已清理。

<a id="dependency-scan-evidence"></a>

### 依赖与扫描证据（2026-10-23）

本轮 clean 构建后的 SBOM 生成于 `2026-10-23T15:00:33Z`，包含 64 个组件（9 个项目子模块、55 个外部组件）。已核对 Spring 的 5 个组件统一为 `7.0.10`、Boot 的 12 个组件统一为 `4.1.2`、Netty 的 11 个组件统一为 `4.2.19.Final`；Jackson core/databind 均为 `tools.jackson.core:3.1.8`，Lettuce 为 `7.5.2.RELEASE`、SnakeYAML 为 `2.7`、SLF4J 为 `2.0.20`、Logback core/classic 均为 `1.6.5`。SBOM 未发现同一 Maven 坐标同时出现多个版本，也未发现 Spring 5、Netty 4.1、旧 `com.fasterxml.jackson.core:jackson-core/jackson-databind` 或 Jackson 2 datatype/module/dataformat 组件。仍存在的 `com.fasterxml.jackson.core:jackson-annotations:2.21` 是 Jackson 3 沿用的注解库，不是旧 databind 残留。

实际 demo fat jar 的 `BOOT-INF/lib/` 也仅包含 `logback-core-1.6.5.jar` 和 `logback-classic-1.6.5.jar`；已读取两个内嵌 jar 的 `Implementation-Version` 并核对 SHA-256 与扫描报告一致。clean 重建后的 fat jar SHA-256 为 `4524112d295d0221d7bee80340bcecbfd61f63f96ec1d9dd8176619e0cf15aea`，已重新完成上述 24 项单机与 TLS 演练。

Dependency-Check 报告时间为 `2026-10-23T15:00:40Z`。38 条顶层依赖记录中有 7 条 reactor SNAPSHOT 虚拟依赖，另有 27 条归并在相关依赖中的记录；这与 SBOM 的组件计数口径不同。报告及相关依赖中没有漏洞条目、被抑制漏洞或 `analysisExceptions`，抑制文件仍为 0 条规则；日志显示 NVD CVE、Known Exploited Vulnerability 等分析器执行完成，11 个模块 clean 构建成功，`failBuildOnCVSS=7.0` 和 `failOnError=true` 保持不变。

NVD 使用 [Dependency-Check 官方镜像](https://dependency-check.github.io/DependencyCheck/data/mirrornvd.html)。扫描前于 `2026-10-23T14:19:23Z` 独立读取 `cache.properties`，其 `lastModifiedDate=2026-10-22T15:44:59Z`，年龄约 22.57 小时，满足不超过 72 小时且不超前 5 分钟的 CI 规则。本次扫描复用本地数据库：报告的最近检查时间为 `2026-10-23T13:22:12Z`，数据修改时间与镜像一致；日志因检查间隔未到而复用 NVD 和 CISA KEV 缓存，没有声称重新全量导入。

同日以只读 GitHub API 核对默认分支的 7 条 open Dependabot 告警（1 high、2 medium、4 low），并逐项比较本轮 SBOM 的 `SnakeYAML 2.7`、`logback-core/classic 1.6.5`。这 7 条已知告警的受影响版本范围均不包含当前解析版本；未修改或关闭仓库告警。具体范围如下，Logback 多维护线均已核对：

| 告警 / 严重度 | 组件与受影响范围 | 已发布修复版本 |
|---|---|---|
| [CVE-2022-1471](https://github.com/advisories/GHSA-mjmj-j48q-9wg2) / high | SnakeYAML `<= 1.33`；该条是 push 提示中的唯一 high | `2.0` |
| [CVE-2026-10532](https://github.com/advisories/GHSA-jhq6-gfmj-v8fx) / low | Logback core `< 1.5.34` | `1.5.34` |
| [CVE-2026-9828](https://github.com/advisories/GHSA-p47f-322f-whfh) / low | Logback core `<= 1.5.32` | `1.5.33` |
| [CVE-2026-1225](https://github.com/advisories/GHSA-qqpg-mvqg-649v) / low | Logback core `< 1.5.25` | `1.5.25` |
| [CVE-2025-11226](https://github.com/advisories/GHSA-25qh-j22f-pwp8) / medium | Logback core `< 1.3.16` 或 `>= 1.4.0, < 1.5.19` | `1.3.16` / `1.5.19` |
| [CVE-2024-12801](https://github.com/advisories/GHSA-6v67-2wr5-gvf4) / low | Logback core `< 1.3.15` 或 `>= 1.4.0, < 1.5.13` | `1.3.15` / `1.5.13` |
| [CVE-2024-12798](https://github.com/advisories/GHSA-pr98-23f8-jwxv) / medium | Logback core `< 1.3.15` 或 `>= 1.4.0, < 1.5.13` | `1.3.15` / `1.5.13` |

**人工补充发现与修复：** [CVE-2026-19880](https://github.com/advisories/GHSA-9mh8-hq67-v26g) 的范围为 Logback classic `0.9.14–1.6.2`，[CVE-2026-104721](https://github.com/advisories/GHSA-5f86-rhcp-cxm6) 为 `0.9.14–1.6.4`；此前 Boot 管理的 `1.5.38` 在范围内，本轮已通过 core/classic 成对覆盖为 `1.6.5` 修复这两个问题。二者均为 CVSS v4 `6.3` / medium；GitHub 全局记录当前为 unreviewed 且未列出包匹配条目，升级前 Dependency-Check 和上述仓库告警均未报告。触发条件是攻击者可控制 MDC discriminator 值，应用再通过 `SiftingAppender` 内嵌的 `FileAppender` 将该值用于文件路径。仓库未配置 `SiftingAppender`，但 `MdcUtil` 会写入请求 URI 等 MDC 值，下游自行配置此路径可能触发旧版本缺陷，因此采用依赖修复而未添加抑制。[Logback 官方发布记录](https://logback.qos.ch/news.html#1.6.5) 说明 `1.6.3` 的首轮修复仍可绕过，`1.6.5` 提供后续完整修复；实际 SBOM、扫描记录和内嵌 jar 已共同确认该修复版本。

**覆盖限制：** Sonatype OSS Index 因未配置凭据而禁用，未取得该来源的本轮结果；test scope 按配置排除。此结果是当前依赖与已有漏洞数据的匹配结论，不是源代码安全审计，也不证明所有潜在漏洞、应用额外依赖或所有 CI 均已通过。Logback 两项问题在升级前漏报，说明工具的 0 告警仍需与官方安全记录人工交叉核对；本轮可确认的是上述 Dependency-Check 门禁通过及两项已知问题采用官方修复版本，不能据此表述为“全库安全无漏洞”。

本轮最终 clean 构建证据已复制到忽略目录 `.m2/scan-2.0.0-logback165/`，包括扫描 JSON/HTML/SARIF、`dependency-scan-2.0.0-logback165-clean.log`、SBOM JSON/XML、镜像输入、官方告警记录和含实际 jar 校验的汇总，避免后续 `clean` 删除。扫描 JSON 的 SHA-256 为 `035a74ed0e37b660650bf9839de7639004af5dde6f2c8fb62a658454be85af52`；SBOM JSON 的 SHA-256 为 `e9d3445841416ab11b1e440a2249499b3b762ddec36222dff2a3ed0687ff258b`。升级前证据另保留于 `.m2/scan-2.0.0/`。

远端 [CI 39974771937](https://github.com/BerryWang1996/netty-spring/actions/runs/39974771937) 验证源码提交 `2c2d61a732ead13a12f071b54d294e1eac77c25b`，Maven Wrapper 为 `3.9.9`。已下载三份测试报告，XML 记录的 Temurin 版本分别为 `17.0.20.1+1`、`21.0.12.1+1-LTS`、`25.0.4.1+1-LTS`；每个均 689 项全执行通过。远端 SBOM（`15:07:58Z`）的 64 个组件坐标和版本与本地最终 SBOM 一致；扫描（`15:08:19Z`）同为 38 条顶层/27 条相关依赖、0 漏洞/0 抑制/0 分析异常。镜像新鲜度检查记录年龄 23.4 小时并通过门禁，随后复用缓存，OSS Index 同样未启用。五份 artifact、job 日志及独立汇总保存在 `.m2/ci-2c2d61a/`；CI 页面保留远端上传件。

后续证据直接更新本页，不新增平行迁移报告。确认支持的模式须写明版本、配置、实际运行结果和未覆盖项；出现失败或跳过时保留事实，按[开发计划](development-plan.md)推进剩余验收。
