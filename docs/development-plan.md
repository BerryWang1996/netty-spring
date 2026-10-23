# 开发计划与阶段状态

更新时间：2026-10-23

本文件是**当前版本状态、下一版本范围和迁移步骤的唯一入口**。历史交付以对应 release notes 为准；集群设计见 [cluster-design.md](cluster-design.md)，发布操作见 [release-checklist.md](release-checklist.md)，推广执行见 [promotion-playbook.md](promotion-playbook.md)。

## 当前状态与顺序

| 版本 | 状态 | 范围与下一步 |
|---|---|---|
| `1.10.0` | 已发布 | Boot 2.7.18 / JDK 17；房间定向投递、离线队列、用户寻址、多设备 presence、mesh 已交付，详见 [发布说明](release-notes-1.10.0.md)。消息历史和逐设备寻址未交付。 |
| `1.10.1` | 本地验证完成，待发布 | 修复集群 starter 缺少可选 `jnats` 时的类加载问题，版本与发布资料已齐；验证证据、扫描及远端发布待办见 [发布说明](release-notes-1.10.1.md)。 |
| `2.0.0` | 方向确定，尚未开始实现 | **Spring Boot 4.1.x 纯迁移**：基于 `1.10.1` 保留现有功能与默认行为，完成依赖、自动装配、Jackson、Netty、集群回归和用户迁移验证。 |
| `2.1+` | 候选 backlog，未承诺版本和时间 | 集群扩展、可观测性和安全增强按真实需求分别立项，不阻塞 `2.0.0`。 |

执行顺序：**收尾 `1.10.1` → `2.0.0` 迁移与验收 → 发布迁移资料及推广 → 评估后续功能**。本次计划整理不代表已经启动 `2.0.0` 代码升级。

## 1.10.1 收尾

范围为缺少 jnats 的启动修复、弃用未生效的 `@AutowiredMessageSender`、测试稳定性修正及补丁发布准备；继续使用 Boot 2.7.18 / JDK 17。版本、测试、SBOM、签名与远端发布的完成情况统一记录在 [1.10.1 发布说明](release-notes-1.10.1.md)，不在此重复检查表。Boot 3 兼容跳板、activation 替换和新功能不纳入本补丁。

`1.x` 在 `1.10.x` 收束，后续主线转向 `2.0.0`。Boot 2.7 已结束 OSS 支持，不承诺为上游提供完整安全回移，也不再宣称“维护 1.7.x 直到 2.1”。迁移期间出现必须修复的项目自身缺陷时，单独评估补丁；公开状态以实际发布说明为准。

## 2.0.0 目标与兼容边界

- **唯一目标运行基线：Spring Boot 4.1.x / Spring Framework 7.x / JDK 17+**。编译仍使用 `--release 17`；开工时锁定当时的 4.1.x 正式补丁及其 BOM，不使用浮动版本。
- 不发布 Boot 3 过渡制品，不承诺同一制品同时支持 Boot 2/3/4。跨代废弃 API 按官方指南逐段检查；必要时用临时分支定位问题，不额外形成维护线。
- 以 `1.10.1` 的注解、业务配置键、SPI 语义、单机默认模式、集群协议和投递行为作为回归基线。Jackson 类型相关公开 Java 签名允许随 major 版本调整，但必须逐项列出替换方式；不承诺二进制兼容。
- Boot 配置、Actuator 输出及第三方默认值可能变化，须与 netty-spring 自有配置分别核对，不能笼统承诺“原 application.yml 全部无改动可用”。
- 不新增 sharded pub/sub、Observation bridge、native-image 支持、虚拟线程优化、IM 原语或安全功能；这些能力独立排入后续版本。

官方 [Boot 系统要求](https://docs.spring.io/spring-boot/system-requirements.html)确认 Java 17 基线；[Framework 7 升级说明](https://github.com/spring-projects/spring-framework/wiki/Spring-Framework-7.0-Release-Notes)列出 Netty 4.2、JUnit 6 等基线变化。开工时复核 [Boot 4.0 迁移指南](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide)和 [Boot 4.1 升级说明](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes)，覆盖 4.1 对 4.0 废弃 API 的移除。

## 源码影响与目标矩阵

下表是待实施清单，不是已经验证的兼容性声明。补丁号及最低依赖版本在阶段 1 用 effective POM / dependency tree 确认。

| 位置 | 当前实际情况 | 2.0.0 处理 |
|---|---|---|
| 根 `pom.xml`、子模块、demo | Boot 2.7.18、JDK 17；独立覆盖 Spring BOM、Netty、Logback、SnakeYAML、Testcontainers 等 | 引入 Boot 4.1 BOM，逐项清理旧覆盖；避免 Spring 5/7、Netty 4.1/4.2 混版；验证构建插件与测试依赖 |
| `netty-spring-boot-autoconfigure` | 4 个自动配置仅通过 `spring.factories` 注册 | 增加 `AutoConfiguration.imports`，适配注解、顺序、条件与新模块依赖 |
| `netty-websocket-cluster-spring-boot-starter` | 3 个自动配置已有 factories/imports 双注册；Redis、NATS、Actuator、Micrometer 条件装配 | 统一注册方式，保留可选依赖隔离、用户 bean 覆盖和配置互斥语义 |
| 两个自动配置模块的 health 类和测试 | 使用 `org.springframework.boot.actuate.health.*` | 适配 `spring-boot-health` / `org.springframework.boot.health.contributor.*`；复核 Health、Status 与健康端点集成 |
| `netty-spring-web` | `ServiceHandlerUtil` 使用 `javax.activation.MimetypesFileTypeMap`；`ServiceHandler` 使用 Jackson 2 | activation 切 Jakarta API 与运行时实现，验证 MIME；JSON 路径迁 Jackson 3 |
| `netty-spring-webmvc` | `RequestMappingResolver` / `JsonViewHandler` 创建、配置 mapper | 适配 Jackson 3 构建、异常 API 和默认值，验证请求绑定与响应 JSON |
| `netty-spring-websocket` | `MessageMappingResolver`、`JsonMessage`、`AesGcmMessageCryptoCodec` 使用 Jackson 2；公开签名暴露 mapper 类型 | 迁移 mapper 与异常处理；核对自定义 mapper、JSON 帧及加密消息格式 |
| `netty-spring-websocket-cluster` | `DefaultMessagePayloadCodec` 用 Jackson 处理 `J:` 消息；Lettuce/jnats 可选；含 mesh TCP | 核对跨节点 JSON、客户端升级、mesh framing 与 Netty 4.2 资源生命周期 |
| 四个 starter、demo、`docker-demo/`、CI | 基于 Boot 2.7；CI 主测试 JDK 17；已有多节点 Docker demo | 更新运行/测试依赖，复用现有示例，增加 JDK 21/25 验证 |

Jakarta 改名只处理 Jakarta EE API；目前源码的直接迁移落点是 activation。`javax.crypto.*` 属 JDK API，应保留，禁止批量替换所有 `javax.*`。Health 包可对照 [Boot 4 HealthIndicator API](https://docs.spring.io/spring-boot/api/java/org/springframework/boot/health/contributor/HealthIndicator.html)核查。

## 分阶段迁移步骤

按阶段顺序推进；阶段 2–4 可因编译依赖交叉实现，全部门禁通过后进入阶段 5。提交围绕可审查的变化拆分，RC 标签只对通过对应门禁、可运行的切片建立。阶段不代表固定工期。

### 阶段 1：锁定基线与依赖方案

- [ ] 从已验证的 `1.10.1` 建立 `2.0.0-SNAPSHOT` 开发分支，保存测试结果、依赖树、effective POM 和启动/消息样本。
- [ ] 锁定 Boot 4.1.x 补丁，按 Boot 2.7 → 3.x → 4.0 → 4.1 升级说明检查删除的 API、属性与构建行为。
- [ ] 记录 Spring、Netty、Jackson、Lettuce、Micrometer、jnats、Testcontainers、JUnit、Mockito、Lombok 和日志组件的最终解析版本，每个手动覆盖说明原因。
- [ ] 首选 Boot BOM 管理的 Netty 4.2，消除旧 4.1 BOM 优先级覆盖；重审 Spring/Logback/SnakeYAML 安全覆盖和 Testcontainers BOM，避免机械删除造成版本倒退。
- [ ] 检查 Maven Wrapper、Compiler、Surefire 和 Boot Maven Plugin 与新测试/打包链的兼容性。

**验收：**依赖方案与源码清单可复现，Jackson 公开 API 变化、可选依赖风险和基线测试通过/失败/跳过均有记录。切换 BOM 后识别的编译错误由后续适配阶段清零。

### 阶段 2：Boot / Spring 自动装配与 Jakarta

- [ ] 更新自动配置模块与测试所需的 Boot 4 模块、包名；shared/cluster 注册清单与配置类逐一对应。确认 factories 是否还有其他扩展用途，再清理废弃注册。
- [ ] 复核装配顺序、`@ConditionalOnClass` 隔离、`@ConditionalOnMissingBean` 覆盖、属性绑定和 metadata。
- [ ] 迁移 health API、class 条件与测试；验证未引入 Actuator/Micrometer 时 starter 仍可按契约启动。
- [ ] activation 替换为 `jakarta.activation-api` + 合适的运行时实现（例如 Angus），验证常见静态资源 MIME 和未知扩展名回退。
- [ ] 检查 Spring 7 下路由注解扫描、参数名/反射、类型转换及 URI 处理，按实际差异修复。

**验收：**四类 starter 通过真实自动配置发现测试，不能仅依赖显式 `AutoConfigurations.of(...)`；用户 bean 可覆盖；缺失 jnats、Lettuce、Actuator、Micrometer 符合既有启用/禁用契约，`1.10.1` 的缺依赖回归继续成立。

### 阶段 3：Jackson 3 与消息兼容

- [ ] 迁移矩阵中的全部 Jackson 调用和 POM 坐标，主线采用 Jackson 3；`jackson-annotations` 按实际坐标处理。
- [ ] 处理 mapper 构建/配置和异常 API，核对日期、枚举、空值、未知字段、Map/POJO/JsonNode 及二进制消息的旧行为。
- [ ] 列出暴露 Jackson 类型的构造器、getter/setter、扩展点的前后签名，补自定义 mapper/codec 的用户迁移示例。
- [ ] 用 `1.10.1` 固定样本验证 HTTP JSON、WebSocket JSON、本地/跨节点投递、crypto JSON；确认 `J:` 消息无二次 JSON 编码，错误回退语义一致。
- [ ] 保留 envelope、HMAC、离线队列和 mesh 帧格式约束；若序列化默认差异不能消除，明确影响并修订迁移/混部方案。

**验收：**项目 JSON 路径适配 Jackson 3，消息契约回归通过，公开 Java API 变化均有替换方式。Boot 的兼容默认值开关不自动作用于源码中手工创建的 mapper，不能代替这些验证。改包与兼容策略参见 [Spring 官方 Jackson 3 说明](https://spring.io/blog/2025/10/07/introducing-jackson-3-support-in-spring/)。

### 阶段 4：Netty 4.2 与集群客户端

- [ ] 适配 HTTP、WebSocket、TLS、压缩、分片和 mesh pipeline；检查 ByteBuf 引用计数、线程组、连接关闭、背压与超时。
- [ ] 回归 Redis standalone / Redis Cluster、NATS / JetStream、mesh 的既有支持组合，明确原本不支持的组合，避免迁移顺带扩大承诺。
- [ ] 验证广播/单播/远程关闭、自投递抑制、房间定向、用户寻址、离线回填、presence、HMAC、MDC trace 及可靠投递。
- [ ] 验证中间件中断、重连、DEGRADED/RESYNC、节点下线与资源回收；针对 Lettuce 协议默认值、重订阅和 NATS API 差异做回归。
- [ ] 同机器、负载和配置下比较旧版与迁移版连接数、吞吐、延迟、线程/内存及丢失/重复计数；定位明显退化并记录取舍。

**验收：**既有支持组合通过真实中间件测试，Netty 版本统一，关键资源无泄漏，协议语义无意外变化；性能结论附复现条件，不从依赖升级推导容量提升。

### 阶段 5：运行矩阵、示例与升级/回滚演练

- [ ] CI 运行 Boot 4.1.x × JDK 17/21/25，编译基线仍为 17；全 reactor、自动装配隔离和 demo smoke 通过，集成测试在有 Docker 的环境执行。
- [ ] demo 验证启动、HTTP、WebSocket roundtrip、已有 crypto/auth profile、健康和指标；复用 `docker-demo/` 双节点 + LB + Redis smoke 验证跨节点 JSON。
- [ ] 同时运行 `1.10.1` 与 `2.0.0` 节点，对适用传输组合验证混部互通、可靠重放、离线数据读取、HMAC 和节点切换。
- [ ] 按下节用户步骤升级并实际回滚至完整旧版应用。混部未通过时提供停机/整体切换方案，不宣称零停机滚动升级。
- [ ] 汇总测试数、跳过原因、依赖树、协议样本、性能比较和演练结果；缺外部环境的用例仍是未完成门禁。

**验收：**每个宣称支持的 JDK、starter 和部署模式均有证据，已知差异、支持矩阵与回滚限制明确。

### 阶段 6：发布收口

- [ ] 将演练验证的迁移步骤、API/配置差异、回滚方式写入 `2.0.0` 正式发布说明；本计划保留状态/索引，避免维护两份操作指南。
- [ ] 同步 README、依赖治理、配置/API 文档、示例和发布清单；移除失效配置例子与旧 Boot 3 目标，历史发布记录标注其规划已被取代。
- [ ] 完成发布清单中的审查、测试、SBOM、漏洞 triage、源码/Javadoc 制品和签名校验；满足门禁后再建 GA 标签和发布。
- [ ] 确认 Central 坐标、示例可复现和升级链接可达，更新稳定版状态并启动对应推广材料。

**2.0.0 完成条件：**各阶段门禁全部有结果；Boot 4.1.x 上现有单机/集群能力可用；用户可执行升级与回滚；没有未披露的关键测试跳过或兼容性断点。

## 用户迁移与回滚准备

本节是阶段 5 的演练输入，尚不是可直接执行的 GA 升级指南。

1. **备份与盘点。** 保存旧应用制品/镜像、POM 版本、配置和 Redis/NATS 数据备份，记录 broker、registry、可靠投递及自定义 SPI/mapper；先以 `1.10.1` 建立可回滚基线。
2. **一起升级依赖。** 应用 Boot/BOM 切至已验收的 4.1.x 补丁，netty-spring 切至 2.0.0；检查应用自己的 Spring/Jakarta/Jackson API、第三方 starter 和旧 Spring/Netty 覆盖。
3. **处理明确的差异。** 按最终签名清单迁移 Jackson 扩展，核对 Boot 配置、健康端点和日志变化，保留 netty-spring 自有配置键与业务行为。
4. **预发布验证。** 运行业务回归、启动 smoke、跨节点消息和故障恢复，比对既有消息样本。仅对混部演练通过的组合使用滚动升级，否则安排整体切换。
5. **回滚。** 按演练方案停止新版本接流量，恢复完整旧应用、依赖和配置；先确认旧版能读取升级期间的数据及可靠投递状态，避免仅替换 jar 或盲目清空共享数据；可靠投递仍按现有 at-least-once 语义处理重复。

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
