# Release Notes — 2.0.0（发布候选）

更新时间：2026-10-24

## 当前状态与范围

**当前 11 个 POM 已统一为 `2.0.0`，仍为未发布的正式版本候选。** 依赖与源码适配、状态计数去重及异常生命周期修复已完成，本地全量及 `467444b` 远端 JDK 17/21/25 均为每套 710 项/123 份 XML、零失败/错误/跳过；SBOM、依赖扫描和 Docker smoke 通过。最终候选的 34 份制品与签名审计、24 项应用 smoke、6 阶段混部回滚、Redis 持久化 9 阶段、Redis Cluster 7 阶段、传输 70 组检查、14 轮负载及 8 个教程场景均已通过。此前 CI 的订阅就绪竞态、负载工具周期探针干扰均已修正并复验。最终提交验证、GA 标签、Central 发布及公开制品消费者复验仍待完成，不将历史 SNAPSHOT 负载替代最终候选结果。

本页是本次实施结果、用户迁移差异和验证证据的唯一记录。执行顺序、剩余任务和验收门槛继续统一在[开发计划](development-plan.md)；发布操作沿用[发布清单](release-checklist.md)。本页不另建一套迁移计划。

本版以 [1.10.1](release-notes-1.10.1.md) 为功能和消息契约基线，目标为 Spring Boot 4.1 迁移，不新增集群协议或业务能力。`2.x` 不与 Boot 2/3 共用同一制品，也不承诺与 `1.x` 二进制兼容。以下迁移方式用于预发布验证，待本页补齐证据后才能作为对应部署模式的正式升级依据。

## 已锁定的运行基线

| 组件 | 当前版本或配置 | 管理方式 |
|---|---|---|
| Java | JDK 17 起；编译 `--release 17` | 当前候选 JDK 17 本地通过，`467444b` 的 JDK 17/21/25 远端矩阵通过 |
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
- HTTP/WebSocket 服务及 mesh 的 NIO 线程组改用 `MultiThreadIoEventLoopGroup` 配合 `NioIoHandler.newFactory()`，保留 NIO transport 和现有生命周期职责。线程退出、连接关闭与正常及已识别异常资源回收回归已纳入当前候选的 710 项全量验证；最终制品 14 轮负载的资源比较和恢复检查亦已通过，条件与边界见下方记录。
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

<a id="upgrade-rollback"></a>

## 用户升级与完整应用回滚

以下界定本次 Boot 4.1 纯迁移的验收范围，不代表 GA 已完成。同版本功能回归与跨版本部署验证分别记录：当前候选的 710 项是全 reactor 的单测、真实中间件、装配与 E2E 测试总数，不是 710 项完整应用混部测试；此前远端 SNAPSHOT 为 694 项。现有单机、Redis、NATS/JetStream、mesh 能力及可选功能继续按既有支持组合使用；缺少某条跨版本混部证据不等于删除该同版本能力。

| 部署范围 | 同版本与跨版本证据 | 升级方式与保留数据回滚范围 |
|---|---|---|
| 单机 HTTP / MVC / WebSocket | 同版本回归及完整应用 smoke 通过，包括已测 crypto/auth/TLS 配置 | 整体切换、恢复完整旧应用；内存会话由客户端重连，不承诺会话迁移 |
| Redis standalone 普通 Pub/Sub，附加功能关闭；HMAC 关闭或 strict 同密钥 | 完整应用旧新、双新、双旧及节点重建演练通过 | 可按已演练方式逐节点滚动升级/回退，保留同一 Redis；不是零停机，Pub/Sub 在途消息仍为 at-most-once |
| Redis standalone 的 reliable / offline 等附加功能 | 同版本功能回归通过；跨版实测 Streams backlog/未 ACK 重放、离线 API/锁/TTL，未测浏览器上线回填及 room/presence 混部 | 完整应用整体切换；已验证旧 classpath 读取新版 Streams/离线数据，保留队列、consumer group/pending 和命名空间，不扩大为所有功能滚动兼容 |
| Redis Cluster 普通 Pub/Sub + registry/heartbeat/reaper | 同版本客户端回归；跨版三主节点状态读写、消息及完整旧 classpath 回退通过 | 完整应用整体切换，保留同一集群；已测旧版读取新版注册状态。room/reliable/offline/presence 仍不支持，启用时的拒绝已验证 |
| NATS Core + Redis registry（mixed） | 同版本装配及真实中间件回归；跨版 Core 传输/重启恢复已测 | 完整应用整体切换，保留 Redis；Core 无 backlog。启用 reliable 时仍使用 Redis Streams，数据回退范围见对应行 |
| all-NATS KV registry + 可选 JetStream reliable | 同版本 KV/JetStream 及配置选型回归通过；跨版只验证 Core/JetStream 传输与 durable backlog，不含 KV 状态连续性 | 整体切换；保留 stream/durable consumer，已测旧版读取新版 backlog。需要延续活动 KV 状态的回退须在应用预发布环境补证，不能据 JetStream 结果推导 |
| mesh + standalone Redis | 同版本实际 TCP、装配及 interest-routing 回归；跨版传输/framing/HMAC 已测，不含 interest/room/presence 混部 | 整体切换，保留 Redis，由重新连接重建会话/路由；mesh 本身无 backlog，附加持久化路径按对应 Redis 数据契约处理 |

Sentinel、自定义 SPI 及未列出的配置没有本轮完整应用混部证据，使用整体切换并验证应用自身的数据恢复方案。本次不新增 failover、resharding 或全部可选配置组合的跨版本保证；详细实测样本与覆盖限制见下方记录。

1. **准备完整应用与数据基线。** 保存可启动的 `1.10.1` 制品/镜像、完整依赖、配置和中间件一致性备份；记录自定义 mapper/codec/SPI、TLS、数据 prefix/URI、用户身份解析及 durable consumer/nodeId。保持中间件版本和拓扑不变，避免与 Boot 迁移同时切换。
2. **重建并预验新应用。** 同步升级 Boot/BOM、netty-spring 和第三方 starter；处理前述 Jakarta、health、Jackson 与 `-parameters` 差异，清理冲突覆盖，重新编译和打包整个应用。用实际配置验证启动、HTTP/WebSocket、健康/指标以及所用消息和持久化路径；记录版本、配置、序号与 ACK 结果。
3. **按矩阵切换。** 已演练的基础 Redis Pub/Sub/HMAC 模式逐节点摘流、drain、停止旧节点、启动新节点并验证双向消息，再处理下一节点。其余模式安排维护窗口，停止新连接和消息写入，drain 并停止全部旧应用，再启动新应用。活动节点 ID 不得冲突；同一 durable 订阅恢复时保持稳定 nodeId/consumer、URI/prefix、用户身份规则与 HMAC 密钥。
4. **验证后恢复流量。** 确认健康、客户端重连、消息序号和适用的 backlog/pending/ACK 状态，再开放流量。保留 Redis/NATS 数据，不删除 stream、consumer group、bucket 或共享键；控制切换时间在消息保留窗口和 TTL 内。Pub/Sub/Core/mesh 停机窗口无补发保证，可靠路径仍按 at-least-once 处理重复。
5. **完整回退。** 先停止新版接流量和写入，按上述滚动或整体方式恢复完整旧应用、依赖及对应配置，保留中间件数据，核对矩阵所列的数据读取/重放范围后再接流量。对未验证的数据状态先在预发布环境补证；如果新版已接受写入，不能直接覆盖旧备份并称为无损回滚，须先核对和处理这些更新。应用会话及路由通过重新连接恢复，不以清空共享数据代替回退。

## 验证记录

| 检查 | 当前结果 |
|---|---|
| 源码版本 | `2.0.0` 未发布候选；开发分支 `codex/2.0.0-spring-boot-4.1` |
| 远端验证 | `467444b6b41a9249f6b606a083f9e327a3dd1ca1` 的 [完整手动 CI](https://github.com/BerryWang1996/netty-spring/actions/runs/39994248327) 成功：JDK 17/21/25 各 710 项/123 份 XML、零失败/错误/跳过，SBOM、Dependency Scan 成功；[push CI](https://github.com/BerryWang1996/netty-spring/actions/runs/39994197019) 同样三 JDK 全绿，scan 按条件跳过。[Docker smoke](https://github.com/BerryWang1996/netty-spring/actions/runs/39994254450) 的 27 项 Node.js 断言、双向投递及清理通过。此轮尚不含之后的负载工具/文档改动，最终提交仍须验证 |
| Reactor 编译 / 打包 | `2.0.0` 候选 11 个模块编译、打包及严格 release profile 成功；最终 demo fat jar 已完成独立部署，发布包通过签名与内容审计 |
| JSON 契约测试 | 新增 17 项全部通过：JsonMessage 4、MVC 输出 3、HTTP 绑定 3、跨节点 codec 3、crypto 3、WebSocket 未知字段 1 |
| 本地全量 `clean verify` | `2.0.0` 正式版本候选 / JDK 17：11 模块成功，123 份 Surefire XML，710 tests / 0 failures / 0 errors / 0 skipped；2026-10-24 01:02:15 CST 完成，包含全部异常生命周期及独立引用修复。完整日志、逐模块 XML 和 `summary.json` 归档于 `.m2/verification-200-ga/` |
| 自动配置发现 / 可选依赖 / MIME | 8 项新增真实发现测试、2 项 MIME 测试通过；另有 3 项真实 javac 参数元数据、4 项请求体解析和 4 项 allocator 指标回归 |
| 依赖一致性 / SBOM | 最终候选 CycloneDX JSON/XML 根版本 `2.0.0`，64 个组件，同坐标无多版本；未见 Spring 5、Netty 4.1 或 Jackson 2 core/databind 残留，详见下方证据 |
| 漏洞扫描 | Logback core/classic 成对升级 `1.6.5` 后，clean 构建及 Dependency-Check `12.2.2` 复扫成功，CVSS 7.0 门禁通过；38 条聚合依赖记录、工具报告 0 条漏洞告警、0 条抑制、无 analysisExceptions；人工补充的 2 条 Logback 中危已通过该版本修复，OSS Index 未启用，详见[扫描证据与覆盖限制](#dependency-scan-evidence) |
| 变更审查 | 功能、资源、API/装配、安全四视角审查；强制丢弃任务、mesh 绑定失败、连接中断及运行中生命周期请求的独立引用问题均有失败复现、修复与当前 710 项全量回归。制品审计、负载、远端 CI 和发布继续核验，不以源码读审或本地测试替代 |
| 单机、双节点与真实中间件 | 最终候选普通/crypto/auth 16 项及 TLS/gzip/分片 8 项全部通过；当前候选 Docker smoke 通过。集群模块 338 项在本地和修正后的三 JDK CI 均通过；前一轮 presence 首条事件用例失败及处置保留如下 |
| 1.10.1 混部及完整应用回滚 | 最终候选 6/6 阶段 HMAC strict 演练通过：旧新双向、重建新节点后重连、错密钥拒绝/恢复、双新、完整双旧回滚；同一 Redis 容器与验证数据保留，覆盖边界见下方 |
| Redis standalone / Cluster / NATS / JetStream / mesh 跨版本 | 最终候选 Redis 持久化 9/9 阶段、Redis Cluster 7/7 阶段、传输 70/70 组通过；双版本 JVM、实际中间件、积压顺序/ACK/回滚、协议及清理证据见下方，不扩大为所有部署组合保证 |
| WebSocket 状态统计 | 共享 recorder 按身份去重，新增 5 项回归；真实 8 连接的握手、收发和关闭计数精确匹配，received 包含控制帧的口径不变 |
| 教程核心片段 | 中英文教程及两组 API 答案共 6 个 Java 片段，以冻结 GA 库和真实 Boot 自动装配完成 8 个单机/双节点场景、16 项检查；12 个 JVM、18 条 WebSocket 均正常关闭。独立 Central 消费者与外部发表尚未完成 |
| 内存 / 延迟 / 资源回收 | 最终 `2.0.0` 制品的 1,024/8,192 连接旧新对比及各 10 分钟持续测试共 14 轮通过；59,249,171 条计量消息全部正确回显，启动隔离、采样覆盖、GC、恢复与清理通过。不将有限负载结果表述为全场景无泄漏或容量上限 |
| Sources / Javadoc / 签名 | `2.0.0` 的 34 份制品及 34 份签名独立核验通过；6 个源码包内容匹配，270 个 class 均为 Java 17 字节码且与修复后的 SNAPSHOT 基线逐字节一致，31 个运行资源仅允许坐标版本字段差异。尚未上传 Central |
| GA 标签 / Maven Central | 未执行，未发布 |

**候选远端失败与处置：** `0e15576` 的 push CI 与手动 CI 分别在 JDK 17、JDK 21 的 `PresenceCrashReapE2ETest` 首条 ONLINE 断言失败；另有一次 JDK 25 在下载 Actuator POM 时 Central 连接重置。两个工作流均未通过，依赖扫描和 SBOM 因前置失败而跳过，不拼接不同 run 的成功项作为门禁结果。原始日志、6 套测试制品与 1,423 文件哈希清单保存在 `.m2/ci-0e15576/`。

真实 Redis 复现通过暂缓 B 节点 Lettuce SUBSCRIBE 的发送，使 `start()` 已返回而 Redis 只有一个订阅者；在同一发布连接确认首条 ONLINE 已处理后再建立 B 的订阅，后续 ONLINE→AWAY 正常到达而首条事件不会重放。这符合异步订阅及 Pub/Sub at-most-once 契约，支持启动竞态的根因判断；原远端日志本身没有订阅 ACK/首发时序。测试现等待保留 presence channel 的实际 `PUBSUB NUMSUB=2` 才发首条事件，保留原有 ONLINE/OFFLINE 的 4 秒业务断言；生产代码未变。修正后 5 次独立 Maven 调用均为 1 项、零失败/错误/跳过，未增加 reactor 测试数。确定性复现、5 份日志/XML 和清理证据保存在 `.m2/presence-subscription-probe-ga/`；随后 `467444b` 的两套完整远端三 JDK 测试均通过。

**异常生命周期资源检查：** 对照 `v1.10.1` 的源码确认三类缺陷已存在，但本轮没有据此豁免资源门禁。真实线程池分别经 5 秒超时和关闭线程中断进入 `shutdownNow`，复现 HTTP/WebSocket 排队消息各残留 `refCnt=1`、3 个 handler permit 仅归还 1 个；WebSocket 的 onConnected、inactive、transport error、heartbeat timeout、handshake failure 五条排队生命周期路径另复现握手请求引用和连接许可未释放。修复后，已接纳但尚未开始的任务通过 `HandlerSubmitter.DiscardableTask` 释放自身资源，普通 Runnable 也归还处理许可；拒绝提交仍由调用方清理，正在运行的任务保有其引用和许可，丢弃一个任务的清理异常不会阻断其余队列清理。该接口不强行终止用户 handler，也不在丢弃路径执行业务生命周期回调。

Mesh 使用真实占用的 loopback 端口复现绑定失败后线程组未终止，再用实际 NIO channel/promise 和 latch 复现 connect 等待被中断后未关闭的未缓存通道。修复分别清理新建线程组/通道/执行器并保留未由此次启动写入的节点广告，以及取消连接、关闭通道并恢复中断位。两项新增测试先失败、修复后通过。失败/通过日志分别保存在 `.m2/lifecycle-200-handler-*`、`.m2/websocket-lifecycle-discard-*` 和 `.m2/lifecycle-200-mesh-*`；源码对照不等于另行执行了旧版全部测试。

独立审查又以双任务 latch 复现同一会话的运行中回调与排队关闭任务共享请求的并发边界：丢弃关闭任务会使仍运行的 `OnConnected` 读到 `refCnt=0`。现为每个异步生命周期任务单独 retain 请求，执行、丢弃、拒绝或提交异常各自释放其引用；会话原始引用和连接许可仍幂等释放。回归断言强制关闭后运行中回调仍持有一个有效引用并能读取实际内容，回调完成后才归零。8 项新增生命周期测试与原有 51 项 resolver 回归全部通过，证据为 `.m2/websocket-lifecycle-lease-{red,green}.{log,xml}`，独立复核已确认此反例闭环。当前 `2.0.0` 的 710 项全量结果已包含这 3 项后补引用回归，取代切版前 707 项初步结果；原始红/绿日志继续保留为缺陷复现依据。

Javadoc 已设置 `failOnError=true`，并按 [Maven Javadoc 官方说明](https://maven.apache.org/plugins/maven-javadoc-plugin/examples/links-configuration.html) 设置 `detectOfflineLinks=false`，避免无源码 starter 递归探测及独立 Central 文档引用未发布的 reactor 路径。`doclint=none` 仍保留，不宣称所有注释 lint 或 warning 清零。3 个依赖聚合 starter 无 Java 源，因此不生成 sources、Javadoc 为空包，沿用 1.10 已发布规则。

最终 `2.0.0` release profile 于 2026-10-24 01:06:17 CST 成功，随后对提交 `0e155763731f01a2291a99908e6296d6dcc114f2` 完成独立审计：父 POM + 9 库模块共 34 份有效制品/34 份签名，指定主指纹 `09CC1D729D9E1CBBA18DE39E8B24A2A210E4168C` 的 `VALIDSIG` 全部通过；6 个源码包与当前 Java 文件逐字节匹配且各有 Javadoc HTML。270 个 class 均为 major 61，与异常生命周期修复后的 SNAPSHOT 基线完全一致；31 个运行资源除明确的坐标版本字段外保持一致，内嵌 POM 与当前 POM 相同。证据归档于 `.m2/release-artifacts-200-ga-2026-10-23T17-08-49-569Z/`；基线在 `.m2/release-artifacts-200-lifecycle-snapshot/`，早期排错记录继续保留。此项为本地审计，上传时还须核验实际 bundle 和 Central 发布状态。

### 部署演练范围（2026-10-24 CST）

本轮固定 `2.0.0` 候选镜像 `netty-spring-200-ga:2.0.0`，image ID 为 `sha256:025cdeeeb65adb91ac9a32f80a7bfa1a7fce4924e11163608f5e856b97062cf2`，fat jar SHA-256 为 `66a565d5824892a13395490622ecbf1155a37f9575d365b21f173bdeea1d7e08`。入口强制核对实际镜像、fat jar 版本/哈希及嵌套模块，不使用历史 SNAPSHOT 结果替代。Docker 默认入口已排除 release profile 额外生成的 sources/javadoc jar；实际三种 jar 共存时仍选中正确可执行包。

单机使用 JRE `17.0.20.1` 的隔离容器，以真实 YAML 将 Netty 端口设为 `8082`。普通模式 7 项检查覆盖 GET/POST、JSON/cookie、WebSocket 文本/JSON、HTTP 触发广播、health/status；`crypto-demo,auth-demo` 组合的 9 项检查覆盖缺少 token 的两个 403，以及 query token 加密文本和 Bearer token 加密 JSON 往返。全部通过。

同一制品另完成 8 项 TLS/gzip/分片检查：临时 CA 签发含 `localhost` / `127.0.0.1` SAN 的测试证书，HTTPS 的 TLS 1.2/1.3 与 WSS 均保留信任链和主机名校验；HTTPS/WSS 错误主机名都被拒绝。gzip 解压后逐字节一致。显式设 `max-frame-aggregation-buffer-size=8192` 后，跨 UTF-8 字符边界的三段 TEXT/CONTINUATION 帧中途无回包，最终恰好一条完整回显；该功能默认仍关闭。24/24 项和完整 runner 均成功，容器、网络、卷及临时私钥已清理，证据为 `.m2/smoke-ga-200/`。此前两次验证脚本的日志路径/PowerShell 汇总故障另行保留，最终结果没有忽略其错误。

历史 SNAPSHOT 的 nginx + Redis 三阶段旧新、双新及双旧回滚保留于 `.m2/compat-*-200-*`，确认跨节点双向 JSON 与 `originNode`；最终候选使用下述更完整的 6 阶段演练。

最终 `2.0.0` 候选完成 6/6 阶段 HMAC strict 演练：旧新双向 JSON、重建新节点并保留旧节点已有连接/重连新节点、双方密钥不匹配时拒绝跨节点消息、恢复密钥、双新节点和完整双旧回滚。共 10 次正向投递、2 次负向拒绝；负向场景本地回显正常，两端日志明确拒绝 invalid HMAC。全程验证同一 Redis 容器和测试 sentinel 数据保留。新节点原连接在重建时中断，不将重连成功描述为零停机。证据为 `.m2/mixed-ga-200/2026-10-23T17-11-53-742Z/`；自建容器、网络已清理，两个测试端口均可重用。此前 SNAPSHOT 对照保留在 `.m2/mixed-advanced-200/`。

以上应用 smoke 覆盖 Redis Pub/Sub 及其 HMAC 路径，demo 调用普通 `broadcastJson`。可靠投递、离线存储及其他传输由下面的独立 harness 实际调用，不从配置启用推导覆盖。基础证据保存在忽略目录 `.m2/smoke-200-*`、`.m2/compat-*-200-*`。

**状态统计修复：** 已修复 `1.10.1` 中原有的 `/netty/status` 共享 event recorder 重复汇总问题：同一 recorder 只计一次，独立 recorder 和自定义普通 Map 继续累加，会话数仍逐 URI 汇总。新增 5 项回归覆盖共享 3 URI、独立/空 recorder、自定义计数和实例身份；已有控制帧测试明确 Ping/Pong/Close 各增加一次 received。`messagesReceived` 仍统计通过会话/大小检查的入站帧，包含控制帧，不能等同业务消息成功数。

在历史 SNAPSHOT 验收制品上，以真实 8 个连接、3 个 WebSocket URI 映射完成精确状态断言：握手尝试/成功和关闭各为 8；预热发送 6,110、计量发送 29,109，全部回显；`messagesSent=35,219`、`messagesReceived=35,227`，差值为 8 个 Close 帧。会话归零，队列清空，无拒绝或写失败。证据位于 `.m2/load-acceptance-2026-10-23T15-41-52-801Z/`；这轮 2 秒计量用于验证统计修复，不作为容量或持续负载结论。

### 持久化与传输跨版本演练（2026-10-24 CST）

各 harness 分别从完整应用镜像解出依赖，在各自独立 classpath 上编译并运行，不混放旧新 jar。旧版为 `1.10.1`，fat jar SHA-256 `a9d5b382b9f86da3905222b8bec43c827dcbe4bab9d5a7af023f5de4fd530d09`；新版为上文固定镜像/哈希的最终 `2.0.0` 候选，包含状态去重和异常生命周期修复。运行入口核对实际版本；源码、命令、原始事件及制品来源随证据保存。

| 演练 | 已验证范围与结果 | 证据目录 |
|---|---|---|
| Redis 持久化，9/9 阶段 | 真实 Redis `7.4.11` standalone、strict HMAC；旧→新及新→旧的可靠积压按序重放、ACK 后重启不重复；回调阻塞时确认 pending=1，强杀并跨版本重启后重投及 ACK 清零；`sendToUser` 离线写入、读取/投递边界、锁排他/到期恢复、确认后删除、key TTL 与条目年龄淘汰；恢复完整旧版依赖 classpath 后读取新版数据，同一 Redis 容器与 sentinel 保留 | `.m2/state-compat-200-2026-10-23T17-10-21-058Z/` |
| Redis Cluster，7/7 阶段 | 真实 Redis 三主节点、16,384 slots；两版实际配置类创建 Cluster registry/heartbeat/reaper/Pub/Sub；24 条注册数据跨三主节点双向读取/删除，心跳实际到期/续约、reaper 排他/租约到期、strict HMAC JSON 广播/单播、双新后完整双旧 classpath 回滚均通过。room/reliable/offline/presence 显式启用时仍无对应 6 个 bean，4 个公开 API 均拒绝；保留同一 Redis 集群及旧新写入数据 | `.m2/redis-cluster-compat-200-2026-10-23T17-11-59-344Z/` |
| NATS / JetStream / mesh，70/70 组 | 真实 NATS `2.10.29`（JetStream）与 Redis `7.4.11` mesh directory；旧旧、旧新、回滚旧旧的文本/JSON/二进制双向传输；Core/mesh 广播和单播、JetStream 广播；节点替换后的 durable backlog 按发布顺序重放并核对 ACK，旧版读取新版创建的 stream；NATS 重启后两版 DEGRADED→ACTIVE 和已有订阅恢复；Core/mesh 离线单播不重放 | `.m2/transport-compat-200-2026-10-23T17-13-17-912Z/` |

协议检查确认 v2 envelope 的 9 字段、`H1:` HMAC-SHA256 包装、JSON 无二次编码，以及 mesh 的 4 字节大端长度前缀；拆包、粘包正向投递和错误 HMAC、未来版本 `99`、超过 8,192 字节上限的负向检查均实际执行。未来版本负向用例在同一 TCP 流后发送有效帧并等待其到达，再检查拒绝结果；可靠重放检查实际事件顺序及 ACK 状态。两套脚本将异常 worker 退出或清理/清单查询失败计为失败；本轮自建容器和网络已清理，传输演练的 6 个端口均可重新绑定。

**覆盖边界：** Redis standalone harness 调用公开持久化 API，`LocalSender` 记录本地投递边界，未验证真实浏览器上线触发回填；其 TTL 结论限于离线 key、条目年龄和 drain lock。Redis Cluster harness 注册实际版本的配置类并绑定属性，未运行 HTTP/WebSocket 服务；三主节点无副本，未测试 failover、resharding 或浏览器连接。JetStream 本轮覆盖尚未消费的 durable backlog，未覆盖回调处理中强杀后的未 ACK 重投。Mesh 本轮验证传输及 framing，未覆盖跨版本 interest/room/presence；all-NATS KV 跨版本自动装配亦未覆盖。这些 harness 支撑上方矩阵的协议/状态兼容范围，不替代完整应用混部、持续容量或生产 SLI 验收；持续负载下的资源/性能和最终发布门禁仍单独保留。

### 持续与目标连接规模负载（2026-10-24 CST）

最终 `2.0.0` 候选完成 **14/14 轮**：1,024/8,192 连接各 3 轮旧新对比，另以 8,192 连接各运行一次 600 秒持续测试。生产源码为 `0e15576`，使用上文固定的 GA 镜像/制品；运行器和独立分析器来自 `a947966`。每轮实际验证版本、镜像 ID、fat jar 哈希和 JRE，未沿用历史 SNAPSHOT 结果。

两版均为 Temurin `17.0.20.1+1`、应用 2 CPU/1536 MiB（禁 swap）、G1、固定 512 MiB heap/256 MiB direct 上限、4 个 Netty I/O 线程和 4 个业务线程、日志 OFF；allocator 保留各版本默认。客户端固定 Node `22.23.3`/`ws 8.21.0`、4 workers、4 CPU/1536 MiB，位于同一私有 Docker 网络。短轮旧新交替运行，预热 15 秒、计量 60 秒；每连接最多 1 条在途消息，半数 `/ws/test` TEXT、半数 `/ws/json`，消息体为含唯一轮次/连接/序号的 1024 字节文本，JSON 额外封装不计入此长度。

下表为每版 3 轮指标的中位数和范围。延迟是逐条 ACK 的 0.1 ms 直方图上界，包含窗口后收齐的尾部消息；ACK/s 仅计固定窗口内到达的 ACK。

| 连接数 | 版本 | ACK/s 中位数 [最小, 最大] | 每轮 p50 / p95 / p99 中位数，ms |
|---:|---|---:|---:|
| 1024 | 1.10.1 | 32,857.47 [32,696.18, 33,245.03] | 30.8 / 35.1 / 40.5 |
| 1024 | 2.0.0 | 32,953.33 [32,760.75, 33,002.33] | 30.7 / 35.2 / 43.1 |
| 8192 | 1.10.1 | 30,606.97 [29,894.63, 31,882.55] | 265.0 / 294.5 / 341.1 |
| 8192 | 2.0.0 | 30,726.98 [29,896.80, 31,297.78] | 264.8 / 289.2 / 321.6 |

1024 连接时，新版吞吐中位数变化 `+0.29%`、p95 `+0.28%`、p99 `+6.42%`；8192 连接时分别为 `+0.39%`、`−1.80%`、`−5.72%`。这些是本次受限条件下的回归观察，不代表普遍性能提升。

两次 600 秒持续测试，旧版发送/ACK `18,002,297`、窗口内 ACK/s `29,990.17`、p50/p95/p99 `270.9/301.6/332.1 ms`；新版发送/ACK `18,282,340`、ACK/s `30,456.91`、p50/p95/p99 `266.6/293.6/325.0 ms`。两版各有 8192 条尾部 ACK。按完整测量窗口取首、末各三分之一（每段 200 秒、40 个有效资源样本）：

| 版本 | VmRSS 均值首段 → 末段，MiB | 全窗口 VmRSS 峰值，MiB | 原生线程 | 全窗口 GC 次数 / 总停顿 / 最大停顿，ms |
|---|---:|---:|---:|---:|
| 1.10.1 | 496.511 → 496.849 | 496.902 | 31 | 560 / 4984.468 / 21.449 |
| 2.0.0 | 471.762 → 471.464 | 471.984 | 32 | 351 / 3018.021 / 23.782 |

持续计量 CPU 均值旧/新为 `201.41%/201.25%`（100% 为一核），接近 2 CPU 配额。全部轮次无 Full GC；10 分钟内首末段 RSS 均值变化不足 1 MiB、线程数保持稳定，不由此推导更长时间或所有异常路径无泄漏。VmRSS 取 `/proc/1/status`，与 Docker MemUsage、Java heap 分开。每短轮有 12 个有效计量样本、每持续轮有 120 个，均通过退出状态、超时、CPU/RSS/线程解析及阶段完整性检查。持续轮资源覆盖旧/新为 `99.508%/99.501%`；首/尾间隔为 `1.491/1.461 s` 和 `1.327/1.665 s`，最大内部空档均为 `3.851 s`，均满足 15 秒门槛。

14 轮计量发送/最终 ACK 均为 **59,249,171**，其中窗口内 ACK `59,177,502`、尾部 ACK `71,669`；预热发送/ACK 均为 **6,160,731**。独立复算 71,680 条连接 ledger、56 份 worker 记录与直方图，确认逐连接唯一序号、消息内容/类型、JSON 形状和稳定 sessionId；未见丢失、重复、损坏、异常断连、关闭超时或异常 Close code，直方图无溢出。最后恢复快照全部为 `UP`、会话/队列/拒绝/写失败为 0、许可 `16387/16388`，剩余一个由读取状态的管理请求占用；新版精确状态计数匹配流量。旧版共享计数重复问题未用于消息正确性或吞吐计算。

**环境与结论边界：** Windows Docker Desktop 上顺序执行，期间未启动其他 Docker/Java/Maven 测试；用户已有 `mysql-8` 保持运行，仍有轻量文件审查。本证据不能独立证明整台主机完全空闲。客户端 worker CPU 合计约 `295.63–300.65%`（线程口径，非整个客户端容器），8192 连接时 event-loop p99 约 `6.17–7.01 s`，说明客户端调度受压；这是客户端定时器延迟，不能当作服务端 RTT。尾部 ACK 不增加窗口吞吐。本轮仅支持相同受限条件下的回归比较，未预设生产吞吐/延迟 SLO，也不确定服务端容量上限。

证据统一位于 `.m2/load-acceptance-2026-10-23T17-33-28-992Z/`：14 组原始 input/result、逐连接 ledger/直方图、client phase、资源/GC 日志、版本及源码来源、readiness、逐轮/全局清理和 `analysis.json`/`analysis.md`。`independent-review.json`/`.md` 的另行复算与归档的 `load-evidence-v2-readiness` 分析器一致，确认完整矩阵、启动探针隔离、采样和全部仪器化门禁通过；本测试项目的容器/网络查询退出 0、未超时且清单为空。`evidence-sha256-manifest.json` 固定 232 个证据文件的字节数和 SHA-256。

**负载工具失败与修正：** 最终候选的首次运行在旧版恢复检查停止：前三快照许可为 `16387/16388`，最后为 `16386/16388`，active task 从 1 变 2，但会话/队列为 0；每约 5.4 秒任务增量 3/4/4，与每 2 秒 Docker 健康请求一致。原始证据不能标识第二任务的 URL，按探针干扰排查，未直接认定泄漏。记录保存在 `.m2/load-acceptance-2026-10-23T17-21-02-686Z/`，不计作通过轮次。运行器现显式关闭镜像/Compose 周期 healthcheck，60 秒内串行检查启动健康，首次 UP 后停止；分析器另核对实际 `Test=[NONE]`、时序和原始结果。恢复仍取最后快照且保留 `availablePermits >= permitLimit - 1`，未放宽阈值。52 项 Node.js 回归及新旧 8 连接短测通过，短测在 `.m2/load-acceptance-2026-10-23T17-27-36-424Z/`，仅验证工具功能。

历史 SNAPSHOT 的 14 轮与其原检查器保存在 `.m2/load-acceptance-2026-10-23T15-54-40-555Z/`，64 连接短探针在 `.m2/perf-200-results-2026-10-23T15-13-48-986Z/`。它们保留溯源，不与本轮最终制品合并统计，也不补造新格式要求的启动隔离证据。


### 教程与 API 示例复验

中英文 WebSocket 教程和两组 API 答案中的 6 个 Java 片段，经明确的 package/import、DTO 和省略依赖脚手架组成 8 个源码文件。JDK `17.0.11` 使用 `--release 17 -parameters` 编译，classpath 只取上文冻结 GA fat jar 的 56 个库，不加载 demo 类；原文片段、生成源码、编译 class 和全部 jar 的清单及哈希在准备、编译和运行阶段交叉核对。单机同时验证 `MessageSenderSupport` 及其 `DefaultMessageSender` 委托，集群验证 `ClusterMessageSender`，均通过真实 `@EnableAutoConfiguration` 发现。

8/8 场景、16 项检查通过：两份实际教程分别运行单机/双节点文本广播，英文另验连接 JSON；第一组 API 示例验证构造器注入、query/session 元数据、跨 endpoint 广播和定向发送；第二组验证 Jackson 3 DTO 绑定、JSON 广播与跨节点定向。双节点使用专用 Redis、strict HMAC、真实订阅及注册人数屏障，每条业务消息只发送一次。12 个 JVM 全部退出 0，18 条 WebSocket 均在请求正常关闭后收到 code 1000；无意外退出/关闭。专用容器/网络已清理，16388/18104/18105 端口实测可重用。

结果在 `.m2/tutorial-ga-200/runs/2026-10-23T18-19-52-159Z/`，清理在 `.m2/tutorial-ga-200/docker-lifecycle-20261023T181805505Z/`。首轮因夹具误把单机代理当直接 sender 而停止，失败保留在 `runs/2026-10-23T18-18-20-610Z/`；修正类型断言后完整重跑，未修改生产代码或放宽消息/关闭断言。此项验证核心 Java 片段，不代表所有文档命令、业务身份认证、TLS、NATS 或容量已复验；独立应用从 Central 的实际依赖解析与复跑，以及外部教程发表仍分别待完成。

<a id="dependency-scan-evidence"></a>

### 依赖与扫描证据

本节当前证据对应 `467444b` 的完整手动 [CI 39994248327](https://github.com/BerryWang1996/netty-spring/actions/runs/39994248327)，生产源码与已签名、部署演练的 `0e15576` 相同，仅修正测试就绪屏障和文档。此前 `0e15576` 两轮 CI 的扫描/SBOM 跳过事实保留在上方失败处置中。

SBOM 根版本为 `2.0.0`，生成于 `2026-10-23T17:25:48Z`，包含 64 个组件（9 个项目子模块、55 个外部组件）。已核对 Spring 的 5 个组件统一为 `7.0.10`、Boot 的 12 个组件统一为 `4.1.2`、Netty 的 11 个组件统一为 `4.2.19.Final`；Jackson core/databind 均为 `tools.jackson.core:3.1.8`，Lettuce 为 `7.5.2.RELEASE`、SnakeYAML 为 `2.7`、SLF4J 为 `2.0.20`、Logback core/classic 均为 `1.6.5`。SBOM 未发现同一 Maven 坐标同时出现多个版本，也未发现 Spring 5、Netty 4.1、旧 `com.fasterxml.jackson.core:jackson-core/jackson-databind` 或 Jackson 2 datatype/module/dataformat 组件。仍存在的 `com.fasterxml.jackson.core:jackson-annotations:2.21` 是 Jackson 3 沿用的注解库，不是旧 databind 残留。

当前实际 demo fat jar 的 `BOOT-INF/lib/` 也仅包含 `logback-core-1.6.5.jar` 和 `logback-classic-1.6.5.jar`；运行入口验证内嵌版本，制品 SHA-256 为上文的 `66a565d5824892a13395490622ecbf1155a37f9575d365b21f173bdeea1d7e08`，24 项单机与 TLS 演练已通过。较早 SNAPSHOT 的 `4524112d...` 哈希仅属于历史 `.m2/scan-2.0.0-logback165/` 证据。

Dependency-Check `12.2.2` 报告时间为 `2026-10-23T17:26:17.254706266Z`，有 38 条顶层、27 条相关依赖记录；此次 GA 虚拟依赖为 0，不沿用历史 SNAPSHOT 的 7 条计数。此口径与 SBOM 不同。报告及相关依赖没有漏洞条目、被抑制漏洞或 `analysisExceptions`，抑制文件仍为 0 条规则；NVD CVE、Known Exploited Vulnerability 等分析器执行完成，扫描 job 成功，`failBuildOnCVSS=7.0` 和 `failOnError=true` 保持不变。

NVD 使用 [Dependency-Check 官方镜像](https://dependency-check.github.io/DependencyCheck/data/mirrornvd.html)。源镜像 `lastModifiedDate=2026-10-23T15:35:14Z`，CI 读取时年龄 1.8 小时，满足不超过 72 小时且不超前 5 分钟的规则。实际扫描数据库的修改时间仍为 `2026-10-22T15:44:59Z`、最近检查为 `2026-10-23T14:54:08Z`；日志明确在 4 小时检查间隔内复用缓存，不能把源镜像时间当作已经导入数据库的时间。

当时以只读 GitHub API 核对默认分支的 7 条 open Dependabot 告警（1 high、2 medium、4 low），其受影响范围均不包含 `SnakeYAML 2.7`、`logback-core/classic 1.6.5`。2026-10-24 CST 再核对时，当前 open 为下表 6 条 Logback 告警（2 medium、4 low），均指向默认分支 `logback-core:1.2.13`；本任务未修改或关闭告警。原始响应和摘要在 `.m2/ci-0e15576/`。下表保留历史 SnakeYAML 对照，Logback 多维护线均已核对：

| 告警 / 严重度 | 组件与受影响范围 | 已发布修复版本 |
|---|---|---|
| [CVE-2022-1471](https://github.com/advisories/GHSA-mjmj-j48q-9wg2) / high（历史对照） | SnakeYAML `<= 1.33`；最新 open 列表中已无此项 | `2.0` |
| [CVE-2026-10532](https://github.com/advisories/GHSA-jhq6-gfmj-v8fx) / low | Logback core `< 1.5.34` | `1.5.34` |
| [CVE-2026-9828](https://github.com/advisories/GHSA-p47f-322f-whfh) / low | Logback core `<= 1.5.32` | `1.5.33` |
| [CVE-2026-1225](https://github.com/advisories/GHSA-qqpg-mvqg-649v) / low | Logback core `< 1.5.25` | `1.5.25` |
| [CVE-2025-11226](https://github.com/advisories/GHSA-25qh-j22f-pwp8) / medium | Logback core `< 1.3.16` 或 `>= 1.4.0, < 1.5.19` | `1.3.16` / `1.5.19` |
| [CVE-2024-12801](https://github.com/advisories/GHSA-6v67-2wr5-gvf4) / low | Logback core `< 1.3.15` 或 `>= 1.4.0, < 1.5.13` | `1.3.15` / `1.5.13` |
| [CVE-2024-12798](https://github.com/advisories/GHSA-pr98-23f8-jwxv) / medium | Logback core `< 1.3.15` 或 `>= 1.4.0, < 1.5.13` | `1.3.15` / `1.5.13` |

**人工补充发现与修复：** [CVE-2026-19880](https://github.com/advisories/GHSA-9mh8-hq67-v26g) 的范围为 Logback classic `0.9.14–1.6.2`，[CVE-2026-104721](https://github.com/advisories/GHSA-5f86-rhcp-cxm6) 为 `0.9.14–1.6.4`；此前 Boot 管理的 `1.5.38` 在范围内，本轮已通过 core/classic 成对覆盖为 `1.6.5` 修复这两个问题。二者均为 CVSS v4 `6.3` / medium；GitHub 全局记录当前为 unreviewed 且未列出包匹配条目，升级前 Dependency-Check 和上述仓库告警均未报告。触发条件是攻击者可控制 MDC discriminator 值，应用再通过 `SiftingAppender` 内嵌的 `FileAppender` 将该值用于文件路径。仓库未配置 `SiftingAppender`，但 `MdcUtil` 会写入请求 URI 等 MDC 值，下游自行配置此路径可能触发旧版本缺陷，因此采用依赖修复而未添加抑制。[Logback 官方发布记录](https://logback.qos.ch/news.html#1.6.5) 说明 `1.6.3` 的首轮修复仍可绕过，`1.6.5` 提供后续完整修复；实际 SBOM、扫描记录和内嵌 jar 已共同确认该修复版本。

**覆盖限制：** Sonatype OSS Index 因未配置凭据而禁用，未取得该来源的本轮结果；test scope 按配置排除。此结果是当前依赖与已有漏洞数据的匹配结论，不是源代码安全审计，也不证明所有潜在漏洞、应用额外依赖或所有 CI 均已通过。Logback 两项问题在升级前漏报，说明工具的 0 告警仍需与官方安全记录人工交叉核对；本轮可确认的是上述 Dependency-Check 门禁通过及两项已知问题采用官方修复版本，不能据此表述为“全库安全无漏洞”。

当前完整证据归档于 `.m2/ci-467444b/`：两套 CI 的六组 XML 分别确认 JDK 17/21/25 各 710 项/123 份报告、零失败/错误/跳过，共 4,260 次测试；manual run 包含扫描，push run 的扫描按条件跳过。9 份下载制品、3 份完整工作流日志和逐项汇总共 1,501 个文件，均记录 SHA-256。SBOM JSON 的 SHA-256 为 `d1a51318fd7bae46c2ecaa1b2f82dad6e0d1d123058cc18ef5456499b82fe026`；扫描 JSON 为 `7d568e8b11a2187b5ec2900890e0d38a0bb37d112bced1a10391ceb68fde3c5d`。

历史对照继续保留：`e47891d` 的 [CI 39983448870](https://github.com/BerryWang1996/netty-spring/actions/runs/39983448870) 为每 JDK 694 项/120 份报告，见 `.m2/ci-e47891d/`；`2c2d61a` 的 [CI 39974771937](https://github.com/BerryWang1996/netty-spring/actions/runs/39974771937) 为每 JDK 689 项/119 份报告，见 `.m2/ci-2c2d61a/`。早期本地 Logback 修复前后扫描在 `.m2/scan-2.0.0/` 和 `.m2/scan-2.0.0-logback165/`，不与当前 GA 依赖、制品哈希或测试数混记。

后续证据直接更新本页，不新增平行迁移报告。确认支持的模式须写明版本、配置、实际运行结果和未覆盖项；出现失败或跳过时保留事实，按[开发计划](development-plan.md)推进剩余验收。
