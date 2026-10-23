# 开发计划与阶段状态

更新时间：2026-10-24

本文件是**当前版本状态、下一版本范围和迁移步骤的唯一入口**。历史交付以对应 release notes 为准；集群设计见 [cluster-design.md](cluster-design.md)，发布操作见 [release-checklist.md](release-checklist.md)，推广执行见 [promotion-playbook.md](promotion-playbook.md)。

## 当前状态与顺序

| 版本 | 状态 | 范围与下一步 |
|---|---|---|
| `1.10.0` | 已发布 | Boot 2.7.18 / JDK 17；房间定向投递、离线队列、用户寻址、多设备 presence、mesh 已交付，详见 [发布说明](release-notes-1.10.0.md)。消息历史和逐设备寻址未交付。 |
| `1.10.1` | 已提交、推送并打标签；Central 发布处理中 | 修复可选 `jnats` 类加载问题；测试与扫描实际结果、Central 状态统一见 [发布说明](release-notes-1.10.1.md)。外部发布状态不再阻塞主线迁移。 |
| `2.0.0` | 正式版本候选验证中，尚未发布 | **Spring Boot 4.1.2 迁移**：11 个 POM 已统一为 `2.0.0`，依赖、源码及异常生命周期修复进入最终验收；实现事实、API 差异及证据见 [迁移说明](release-notes-2.0.0.md)。 |
| `2.1+` | 候选 backlog，未承诺版本和时间 | 集群扩展、可观测性和安全增强按真实需求分别立项，不阻塞 `2.0.0`。 |

当前工作：**推进 `2.0.0` 迁移与验收 → 满足门禁后发布 → 评估后续功能**。`1.10.1` 外部仓库传播另行核实，不将其处理中状态写成已发布。

## 1.10.1 收尾

范围为缺少 jnats 的启动修复、弃用未生效的 `@AutowiredMessageSender`、测试稳定性修正及补丁发布准备；继续使用 Boot 2.7.18 / JDK 17。版本、测试、SBOM、签名与远端发布的完成情况统一记录在 [1.10.1 发布说明](release-notes-1.10.1.md)，不在此重复检查表。Boot 3 兼容跳板、activation 替换和新功能不纳入本补丁。

`1.x` 在 `1.10.x` 收束，后续主线转向 `2.0.0`。Boot 2.7 已结束 OSS 支持，不承诺为上游提供完整安全回移，也不再宣称“维护 1.7.x 直到 2.1”。迁移期间出现必须修复的项目自身缺陷时，单独评估补丁；公开状态以实际发布说明为准。

## 2.0.0 目标与兼容边界

- **唯一目标运行基线：Spring Boot 4.1.x / Spring Framework 7.x / JDK 17+**。编译仍使用 `--release 17`；已锁定 Boot `4.1.2` 及其 BOM，不使用浮动版本。
- 不发布 Boot 3 过渡制品，不承诺同一制品同时支持 Boot 2/3/4。跨代废弃 API 按官方指南逐段检查；必要时用临时分支定位问题，不额外形成维护线。
- 以 `1.10.1` 的注解、业务配置键、SPI 语义、单机默认模式、集群协议和投递行为作为回归基线。Jackson 类型相关公开 Java 签名允许随 major 版本调整，但必须逐项列出替换方式；不承诺二进制兼容。
- Boot 配置、Actuator 输出及第三方默认值可能变化，须与 netty-spring 自有配置分别核对，不能笼统承诺“原 application.yml 全部无改动可用”。
- 不新增 sharded pub/sub、Observation bridge、native-image 支持、虚拟线程优化、IM 原语或安全功能；这些能力独立排入后续版本。

官方 [Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)确认 Java 17 基线；[Framework 7 升级说明](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes)列出 Netty 4.2、JUnit 6 等基线变化。开工时复核 [Boot 4.0 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)和 [Boot 4.1 升级说明](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)，覆盖 4.1 对 4.0 废弃 API 的移除。

## 源码影响与目标矩阵

下表记录已落地的源码适配及验证重点。实现完成不等于全部发布门禁通过；实际版本和运行证据统一见 [2.0.0 迁移说明](release-notes-2.0.0.md)。

| 位置 | 已实施 | 验证重点 |
|---|---|---|
| 根 POM、子模块、demo | Boot 4.1.2 / Java 17；清理旧 BOM 覆盖，保留必要安全补丁 | 依赖解析统一、插件与 JDK 运行矩阵 |
| shared / cluster 自动配置 | 7 个 `@AutoConfiguration` 通过 imports 注册，清理旧 factories | 真实发现、顺序、用户 bean 覆盖、可选依赖缺席 |
| Health 与 Micrometer | 使用 `spring-boot-health` 与新包，适配 registry 顺序和 allocator 指标 | 健康状态、无 Actuator/Micrometer 启动、默认/pooled 用量及去重 |
| `netty-spring-web` / MVC | Jakarta Activation；Jackson 3；参数名缺失快速报错；按 Content-Type 解析表单 | MIME、路由反射、JSON 正文不被提前消费、HTTP 参数和输出 |
| WebSocket / cluster codec | Jackson 3 mapper 与异常 API；公开 mapper 签名随 major 迁移 | 旧 JSON/crypto 固定样本、自定义 mapper、`J:` 无二次编码、跨版本消息 |
| Netty / Redis / NATS / mesh | Netty 4.2 IoHandler 线程组；Lettuce 7 显式保留旧刷新策略；Testcontainers 2 | pipeline、引用计数、连接/线程回收、真实中间件与故障恢复 |
| 四个 starter、demo、CI | 切至新基线，CI 增加 JDK 17/21/25 | 自动配置、单机/双节点 smoke、混部及完整应用回滚 |

Jakarta 改名只处理 Jakarta EE API；目前源码的直接迁移落点是 activation。`javax.crypto.*` 属 JDK API，应保留，禁止批量替换所有 `javax.*`。Health 包可对照 [Boot 4 HealthIndicator API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/health/contributor/HealthIndicator.html)核查。

## 分阶段迁移步骤

按阶段顺序推进；阶段 2–4 可因编译依赖交叉实现，未完成门禁继续保留。已完成核心适配、状态计数去重和异常生命周期修复；`2.0.0` 候选本地全量及 `467444b` 的远端 JDK 17/21/25 均为每套 710 项、零失败/错误/跳过，SBOM、依赖扫描和 Docker smoke 通过。最终制品签名审计、24 项应用 smoke、6 阶段混部回滚、Redis/NATS/JetStream/mesh 跨版本演练及 14 轮负载均已通过。升级范围限定为基础 Redis Pub/Sub/HMAC 可按已测方式滚动，其余整体切换。教程核心片段的 8 个场景亦已通过；最终提交验证、GA 标签、Central 发布与公开制品消费者复验仍待完成。详细数字、失败处置和各轮源码范围只以[迁移说明](release-notes-2.0.0.md)为准，历史 SNAPSHOT 负载不替代最终制品。

### 阶段 1：锁定基线与依赖方案

- [x] 从已验证的 `1.10.1` 建立 `2.0.0-SNAPSHOT` 开发分支，保留基线制品、验证记录和旧版 JSON/crypto 样本；生成新基线 effective POM。
- [x] 锁定 Boot 4.1.2，按 [Boot 3.0 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-3.0-Migration-Guide)、Boot 4.0 / 4.1 和 Framework 7 升级说明，逐项对照当前 API、配置及打包资源；已检查自动配置、模块拆分、参数名、日志默认值和可选依赖打包差异。运行矩阵验收仍单独保留。
- [x] 记录 Spring、Netty、Jackson、Lettuce、Micrometer、jnats、Testcontainers、JUnit、Mockito、Lombok 和日志组件的最终解析版本，每个手动覆盖说明原因。
- [x] 使用 Boot BOM 管理的 Netty 4.2，移除旧 Spring/Netty/Logback/Testcontainers 覆盖；保留 Jackson 3.1.8、SnakeYAML 2.7 和 Logback core/classic 1.6.5 安全覆盖并注明原因。
- [x] 检查 Maven Wrapper、Compiler、Surefire 和 Boot Maven Plugin 与新测试/打包链的兼容性：远端三个 JDK 测试、SBOM 和实际 demo 打包/启动通过。

**验收：**依赖方案与源码清单可复现，Jackson 公开 API 变化、可选依赖风险和基线测试通过/失败/跳过均有记录。切换 BOM 后识别的编译错误由后续适配阶段清零。

### 阶段 2：Boot / Spring 自动装配与 Jakarta

- [x] 更新自动配置模块与测试所需的 Boot 4 模块、包名；shared/cluster 注册清单与配置类逐一对应。确认 factories 仅用于旧自动配置注册后清理。
- [x] 复核装配顺序、`@ConditionalOnClass` 隔离、`@ConditionalOnMissingBean` 覆盖、属性绑定和 metadata。
- [x] 迁移 health API、class 条件与测试；验证未引入 Actuator/Micrometer 时 starter 仍可按契约启动。
- [x] activation 替换为 `jakarta.activation-api` + Angus 运行时，验证常见 MIME 和未知扩展名回退。
- [x] 检查 Spring 7 下路由、反射、参数绑定与 URI 处理；补 `-parameters` 缺失快速报错，并修复 JSON 正文被表单预解析消费的问题。

**验收：**四类 starter 通过真实自动配置发现测试，不能仅依赖显式 `AutoConfigurations.of(...)`；用户 bean 可覆盖；缺失 jnats、Lettuce、Actuator、Micrometer 符合既有启用/禁用契约，`1.10.1` 的缺依赖回归继续成立。

### 阶段 3：Jackson 3 与消息兼容

- [x] 迁移矩阵中的全部 Jackson 调用和 POM 坐标，主线采用 Jackson 3；`jackson-annotations` 保留官方坐标。
- [x] 处理 mapper 构建/配置和异常 API，回归日期、枚举、空值、未知字段、Map/POJO/JsonNode 及二进制消息。
- [x] 列出暴露 Jackson 类型的构造器、getter/setter、扩展点的前后签名，补自定义 mapper 的用户迁移示例。
- [x] 用 `1.10.1` 固定样本验证 HTTP JSON、WebSocket JSON、本地/跨节点投递、crypto JSON；确认 `J:` 消息无二次 JSON 编码，错误回退语义一致。
- [x] 保留 envelope、HMAC、离线队列和 mesh 帧格式约束：真实跨版本验证 v2 envelope、HMAC strict、持久化 JSON 及 Redis ID、mesh 长度前缀/拆包/粘包；错误 HMAC、未来版本和超长帧负向检查通过。此项确认已测格式，不替代阶段 5 的完整部署组合验收。

**验收：**项目 JSON 路径适配 Jackson 3，消息契约回归通过，公开 Java API 变化均有替换方式。Boot 的兼容默认值开关不自动作用于源码中手工创建的 mapper，不能代替这些验证。改包与兼容策略参见 [Spring 官方 Jackson 3 说明](https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring/)。

### 阶段 4：Netty 4.2 与集群客户端

- [x] 适配 HTTP/WebSocket/mesh 线程组，运行既有 pipeline、ByteBuf、关闭、背压和超时回归；真实 TLS 1.2/1.3、错误主机名拒绝、gzip 和分片聚合 smoke 通过；allocator 指标覆盖新默认实例并去重。
- [x] 回归 Redis standalone / Redis Cluster、NATS / JetStream、mesh 的既有支持组合；保持原不支持组合的拒绝语义。
- [x] 在同版本测试中验证广播/单播/远程关闭、自投递抑制、房间、用户、离线、presence、HMAC、MDC trace 及可靠投递。
- [x] 运行既有中断、重连、DEGRADED/RESYNC、下线和资源回收测试；显式保持原 Lettuce 刷新策略。跨版本恢复验证仍见阶段 5。
- [x] 同机器、负载和配置下比较旧版与迁移版连接数、吞吐、延迟、线程/内存及丢失/重复计数：最终候选的 1,024/8,192 连接各 3 轮旧新对比及各 10 分钟持续测试共 14 轮通过，59,249,171 条计量消息全部正确回显，恢复、资源采样、启动探针隔离及清理门禁通过。原失败与历史 SNAPSHOT 证据保留；结果限于已测受限环境，不作生产容量或全场景无泄漏承诺。
- [x] 完成已识别异常生命周期资源回归：排队 HTTP/WebSocket 请求和 WebSocket 生命周期任务被强制丢弃、mesh 绑定失败和等待连接被中断时，释放实际持有的引用、许可、通道和线程；运行中任务不得提前释放，拒绝提交保持原所有权，重复关闭和清理失败不遗留后续任务。失败复现及修复日志已归档，运行中回调与排队关闭任务的独立引用回归亦通过；均纳入当前 `2.0.0` 的 710 项全量验证。最终制品负载门禁仍见上一项。
- [x] 修复 `/netty/status` 共享 event recorder 重复汇总：按 recorder 实例去重，独立 recorder/自定义普通 Map 仍累加，会话数逐 URI 汇总；新增回归与真实 8 连接精确计数通过。received 保持包含控制帧，业务消息丢失/重复继续以客户端序号核对。

**验收：**既有支持组合通过真实中间件测试，Netty 版本统一；检查关键 ByteBuf、许可、通道和线程组的正常及已识别异常生命周期，关键资源回收有实际断言，协议语义无意外变化。资源结论注明验证范围，性能结论附复现条件，不从依赖升级推导容量提升。

### 阶段 5：运行矩阵、示例与升级/回滚演练

- [x] CI 运行 Boot 4.1.2 × JDK 17/21/25，编译基线仍为 17；正式版本候选 `467444b` 每个 JDK 710 项/123 份报告且零失败/错误/跳过，SBOM、依赖扫描和双节点 Docker smoke 通过。集成测试实际执行，presence 测试启动竞态已确定性复现并修正；后续验收脚本/文档最终提交仍另跑发布门禁。
- [x] demo 完成 YAML 启动、HTTP、WebSocket、crypto/auth profile、health/status smoke；默认 registry 实际指标绑定测试通过；双节点 + LB + Redis 双向 JSON smoke 通过。
- [x] 复验教程核心 Java 片段：中英文教程与两组 API 答案共 8 个单机/双节点场景、16 项检查通过，真实自动装配、消息断言、12 个 JVM/18 条 WebSocket 正常关闭及专用环境清理均有证据；独立 Central 消费者与外部发表另计。
- [x] 按有限支持范围完成混部演练：完整应用仅对基础 Redis Pub/Sub/HMAC 声明已测滚动/重连；Redis standalone 持久化、Redis Cluster 三主节点、NATS/JetStream/mesh 的独立跨版本证据用于协议与状态兼容，其余完整应用采用整体切换。具体配置、未测边界和操作统一见[迁移矩阵](release-notes-2.0.0.md#upgrade-rollback)，不扩展为所有组合或零停机保证。
- [x] 完成双新节点恢复完整 1.10.1 镜像；保留中间件与验证数据，并以完整旧版 classpath 读取新版 Redis standalone 持久数据、Redis Cluster 注册状态和 JetStream durable backlog。矩阵已明确保留数据、稳定订阅身份、重连和未验证状态的回退限制；此项只完成本次迁移演练，不代表 GA 已发布。
- [ ] 汇总最终候选的测试数、跳过原因、依赖树、协议样本、性能比较和演练结果；本地/远端测试、制品、扫描、跨版本演练与完整负载已统一归档，教程核心片段亦已复验，最后待补最终提交验证。原始失败和历史对照继续保留，当前事实统一见迁移说明。

**验收：**每个宣称支持的 JDK、starter 和同版本部署模式均有证据，跨版本升级方式按矩阵限定；已知差异与回滚限制明确。未宣称的混部、failover/resharding 和全部可选配置组合不自动扩成此次迁移门禁。

### 阶段 6：发布收口

- [x] 将演练验证的迁移步骤、API/配置差异、回滚方式写入 `2.0.0` 发布说明；本计划保留状态/索引，避免维护两份操作指南。发布状态继续以实际标签和 Central 结果为准。
- [x] 同步 README、依赖治理、配置/API 文档、示例和发布清单；移除失效配置例子与旧 Boot 3 目标，历史发布记录标注其规划已被取代。正式发布时再更新 GA 状态。
- [ ] 完成发布清单中的审查、测试、SBOM、漏洞 triage、源码/Javadoc 制品和签名校验；功能、资源、API/装配、安全四视角审查发现的异常资源路径纳入本轮修复，并核对最终候选。满足门禁后再建 GA 标签和发布。
- [x] 处置扫描工具未覆盖的 Logback CVE-2026-19880 / CVE-2026-104721：core/classic 成对升级 1.6.5，验证 Java 17 / SLF4J 2.0、实际滚动、MDC 路径拒绝回退、Boot 启动及 24 项应用 smoke，并重新生成 SBOM 和扫描；差异统一见迁移说明。
- [ ] 确认 Central 坐标、示例可复现和升级链接可达，更新稳定版状态并启动对应推广材料。

**2.0.0 完成条件：**各阶段门禁全部有结果；Boot 4.1.x 上现有单机/集群能力可用；用户可执行升级与回滚；没有未披露的关键测试跳过或兼容性断点。

## 用户迁移与回滚准备

配置范围、完整应用滚动/整体切换、保留数据和回退操作统一维护在 [2.0.0 迁移矩阵与步骤](release-notes-2.0.0.md#upgrade-rollback)，本页不重复操作指南。当前仍是预发布验收范围；GA 发布门禁和 Central 可下载性确认后再更新稳定版状态。

## 2.1+ 候选工作

以下项目独立确定需求、设计和验收后才分配版本，不属于 2.0.0 发布门禁。

| 方向 | 进入实施的条件与边界 |
|---|---|
| Sharded pub/sub | 有 Redis Cluster 扩容需求和可复现瓶颈；Redis 7+，按选定 Lettuce 版本核实 API/重订阅。验证多主分片分布、同槽订阅限制、无 pattern 订阅、故障转移/resharding、classic/sharded 连接共存、listener 与发布失败行为。主要减轻 Redis cluster-bus 传播并分摊频道负载，不能宣称减少所有应用订阅者或单热点频道投递数。 |
| Observation / tracing | 保留 `ClusterTraceContext` SPI 与 MDC 传播，独立验证真实 span 续接、采样和关闭开销。 |
| 安全增强 | 先考虑 JWT/OIDC 接入示例、每节点/每会话限流和 mesh mTLS；全局配额、审计/秘密管理、SNI/密钥轮换、供应链签名按需求分别评估，每项独立验收。 |
| IM 扩展 | 真实采用者提出需求后评估历史、messageId/seq、撤回、回执、逐设备寻址；envelope 变更另行设计滚动兼容。 |
| Native / 性能与协议 | native-image、虚拟线程、完整 OpenTelemetry、HTTP/2/3、子协议、多区域/跨 DC 分别立项，附运行条件和证据。 |

Sharded pub/sub 机制以 [Redis 官方说明](https://redis.io/docs/latest/develop/pubsub/#sharded-pubsub)为准；旧研究中的 Lettuce 6.2 起支持和固定比例容量提升不再作为结论。

## 文档维护约定

- **当前路线只改本文件。** 原 `docs/2.0.0/` 三份预研的有效信息与下一方向调研的开发结论已归并；旧范围及未经核验的版本/工期/性能推断不作为实施依据。
- **已发布事实只进对应 release notes。** 不再把已完成 RC 历史逐条复制到当前路线；历史设计/审查记录保留溯源，并标明新计划入口。
- **专题文档各管一件事。** 配置/API、集群设计、依赖治理、发布流程、推广沿用现有入口；迁移事实随 2.0.0 发布说明交付，不再维护平行的未来迁移草稿。
- **状态以证据为准。** “实现”“本地测试通过”“已推送”“已发布”分别记录；新增能力的默认行为、可选依赖边界和测试结果同步更新。
