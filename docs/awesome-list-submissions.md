# Awesome 列表提交材料（待你用 BerryWang1996 账号提 PR）

> 2026-10-24 更新的 **2.0.0 正式版 / Spring Boot 4.1.2 / JDK 17** 提交草稿。Maven Central 全部 68 份发布文件已公开核验，见[发布说明](release-notes-2.0.0.md)；所有外部 PR/提交尚未执行。
> 下方分类与收录流程来自 2026-08 调研；提交前重新核对目标仓库规则、当前收录状态和账号，不沿用旧 star 数、活跃度或账号登录结论。
> 推广顺序统一见[推广计划](promotion-playbook.md)，不以本地草稿完成表示已被收录。
> 两教程/两回答的核心 Java 片段已在全新 Maven 缓存从 Central 解析的独立消费者中完成单机/双节点 Redis 验证，共 8 场景、16 项检查通过。

---

## 目标 1（首选）：akullpp/awesome-java

- **仓库**: https://github.com/akullpp/awesome-java
- **netty-spring 是否已收录**: 2026-08 调研时未收录，当前状态待复查。
- **归属分类**: `Networking`（Libraries for building network clients and servers——
  同类的 Netty / Java-WebSocket / Atmosphere / ServiceTalk / Socket.IO Client Java 都在这里）
- **提交文件**: `README_SOURCE.md`（不是生成的 README.md；star 徽章/许可/排序由生成器自动处理，
  **不要手写** star 或 license 徽章）
- **历史收录标准与项目对应材料**（提交前重新核对）：
  - Java 为一等 API ✓（纯 Java 库）
  - noteworthy：填补有用空档 ✓（Spring Boot 原生 WebSocket 不走 STOMP，带可选 Redis 集群）
  - 英文文档 ✓（README EN + api-guide EN）
  - 许可清晰 ✓（Apache-2.0，SPDX 已知，生成器自动识别，**条目里不要重复写 license**）

### 要提交的条目（直接插入 Networking 分类，Netty 条目之后）

```markdown
- [netty-spring](https://github.com/BerryWang1996/netty-spring) - Spring Boot starters for serving HTTP MVC and WebSocket on Netty, with an opt-in Redis-backed cluster for cross-node broadcast, presence and offline delivery.
```

### PR 建议

- **标题**: `Add netty-spring`
- **正文**: 简短、中立、不吹嘘。示例：

```markdown
Spring Boot starters that serve HTTP MVC and WebSocket on Netty (no STOMP/SockJS required),
with an opt-in Redis-backed cluster for cross-node broadcast, presence and offline delivery.
Apache-2.0 with English documentation. Released 2.0.0 targets Spring Boot 4.1.2 and JDK 17.
Core tutorial handlers have been checked with independently resolved Central dependencies in single-node and two-node Redis modes.
Maven Central availability and consumer verification are complete; this submission has not been made.
```

- 一次 PR 只提交一个项目（规则要求）。
- 若被要求"description 再短一点/更中立一点"，可直接删到：
  `Spring Boot starters for serving HTTP MVC and WebSocket on Netty.`

---

## 目标 2：ityouknow/awesome-spring-boot

- **仓库**: https://github.com/ityouknow/awesome-spring-boot （当前规则与活跃度待复查）
- **收录格式**: `*   [名称 - 中文描述](链接)`（无 star 徽章，中文描述）
- **归属分类**: `Open Source`
- **提交方式**: 见仓库 issue #1（"参与贡献"指引）；可能需要先 issue 申请再提 PR

### 要提交的条目

```markdown
*   [netty-spring - Spring Boot 集成 Netty（HTTP MVC + WebSocket + 可选 Redis 集群）](https://github.com/BerryWang1996/netty-spring)
```

---

## 目标 3（低优先级，可选）：ThomasVitale/awesome-spring

- **仓库**: https://github.com/ThomasVitale/awesome-spring （当前分类待复查）
- **历史判断**: 调研时以书籍/课程/脚手架为主，类库没有合适分类。
  建议跳过，或等有教程/课程产出后再以"Educational GitHub Projects"名义提交。暂不提交。

---

## 历史排除项：awesome-netty 系列

2026-08 调研未选用这些列表。本轮不重新扩展渠道，也不把旧活跃度记录当作当前结论。

---

## 提交后的跟进

- Central 与消费者运行验证已完成；复查目标仓库规则后再决定是否提交，逐项记录实际 PR 链接与处理结果。
- 如被拒，按维护者给出的具体原因处理，不用未经核实的 star 门槛或响应时间推测结果。
- 当前只准备材料，尚未提交或执行外部跟进。
