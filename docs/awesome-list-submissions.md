# Awesome 列表提交材料（待你用 BerryWang1996 账号提 PR）

> 调研日期：2026-08-04。以下收录格式/标准/坐标均为当日对目标仓库实测核对。
> 所有 PR 需由**你的 GitHub 账号**提交（gh CLI 当前登录的是 BoruiWangIxoran，不是 repo owner
> BerryWang1996——提 PR 前先 `gh auth switch` 或用浏览器登录 BerryWang1996）。
> 诚实提醒：netty-spring 当前 **0 star / 0 fork**，`akullpp/awesome-java` 对 self-promotion
> 会仔细审查（但"符合同样标准即欢迎"）。低 star 可能被拒，属正常，不要灰心——内容质量与
> 文档完整性才是被收录的硬标准，这两点本项目是达标的。

---

## 目标 1（首选）：akullpp/awesome-java

- **仓库**: https://github.com/akullpp/awesome-java （**48.7k★**，2026-08-10 仍在活跃维护——首选）
- **netty-spring 是否已收录**: 否（已检索确认）
- **归属分类**: `Networking`（Libraries for building network clients and servers——
  同类的 Netty / Java-WebSocket / Atmosphere / ServiceTalk / Socket.IO Client Java 都在这里）
- **提交文件**: `README_SOURCE.md`（不是生成的 README.md；star 徽章/许可/排序由生成器自动处理，
  **不要手写** star 或 license 徽章）
- **收录标准**（逐条对照，全部满足）：
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
Apache-2.0, English docs, active maintenance (1.10.0 released 2026-06).
```

- 一次 PR 只提交一个项目（规则要求）。
- 若被要求"description 再短一点/更中立一点"，可直接删到：
  `Spring Boot starters for serving HTTP MVC and WebSocket on Netty.`

---

## 目标 2：ityouknow/awesome-spring-boot

- **仓库**: https://github.com/ityouknow/awesome-spring-boot （**4550★**，2024-05 后未更新，较休眠）
- **收录格式**: `*   [名称 - 中文描述](链接)`（无 star 徽章，中文描述）
- **归属分类**: `Open Source`
- **提交方式**: 见仓库 issue #1（"参与贡献"指引）；可能需要先 issue 申请再提 PR

### 要提交的条目

```markdown
*   [netty-spring - Spring Boot 集成 Netty（HTTP MVC + WebSocket + 可选 Redis 集群）](https://github.com/BerryWang1996/netty-spring)
```

---

## 目标 3（低优先级，可选）：ThomasVitale/awesome-spring

- **仓库**: https://github.com/ThomasVitale/awesome-spring （**1299★**，2024-04 后未更新）
- **结论**: 该列表以书籍/课程/脚手架为主，**类库类项目没有合适分类**，强行塞入反而不自然。
  建议跳过，或等有教程/课程产出后再以"Educational GitHub Projects"名义提交。暂不提交。

---

## 已核实不提交：awesome-netty 系列

所有 `awesome-netty` 列表均为死列表（最高 `NorthWard/awesome-netty` 43★，2022 年后无更新），
提交无流量价值。已排除。

---

## 提交后的跟进

- akullpp/awesome-java 维护者响应通常数天内；被拒就问清原因（低 star 最常见），
  不要追问第二次——等 star 涨起来后再补一次 PR。
- 与 SO 草稿、教程发布同节奏：先发内容（教程/SO），有 star 后提 awesome PR，命中率更高。
