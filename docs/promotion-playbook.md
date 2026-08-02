# netty-spring — Promotion Playbook (1.10.0 GA)

> A practical, **honest** launch plan. This project's credibility comes from its honest-scope culture —
> the promotion must match it. Overselling the "node-ceiling break" is the one thing that would burn trust.
> Lead with the *simple* win (one-flag WebSocket on Spring Boot) and let the cluster story earn attention.
>
> Updated 2026-08-03 from the market-research pass (`docs/next-direction-research-2026-08.md`):
> distribution, not product, is the binding constraint (0 stars / 0 issues / cluster starter "Used in: 0").
> Phase-1 rule: flip the free switches first — several proof assets below already exist but are invisible.

---

## 1. Positioning

**One-liner:** *Spring Boot + Netty for HTTP MVC and WebSocket — single-node by default, with an opt-in,
honestly-scoped IM-platform cluster (rooms, `sendToUser`, presence, node-to-node mesh).*

**The wedge (why anyone clicks):** "Add WebSocket to a Spring Boot app with one starter and zero ceremony —
then scale it across nodes with one flag, with the cluster's limits documented as carefully as its features."

**The differentiator that survives scrutiny:** not "infinite scale" — it's **honesty + a clean opt-in SPI**.
Every limit (the ~10-node Pub/Sub ceiling, the random-LB saturation caveat, at-most-once broadcast) is in the
docs and on the site. That candor is the brand.

## 2. Who it's for (ranked)

1. **Spring Boot teams adding realtime** (chat, notifications, live dashboards) who find Spring's native
   WebSocket/STOMP heavy and don't want to bolt on socket.io or a separate realtime stack.
2. **Small/medium IM or live-feature products** (≤ ~10 nodes) that want room/presence/offline primitives
   without adopting a full IM SaaS.
3. **Netty-curious Java devs** who want Netty's performance with Spring Boot ergonomics.
4. **Engineers evaluating "build vs buy" for realtime** — the honest-scope docs help them decide *against*
   it when it doesn't fit, which builds trust for when it does.

## 3. Honest differentiation (vs the alternatives)

| Alternative | Where netty-spring wins | Where it doesn't (say so) |
|---|---|---|
| Spring native WebSocket + STOMP | one-starter setup; cluster + IM primitives built in; Netty perf | STOMP has a broader broker ecosystem (RabbitMQ/ActiveMQ relay) |
| socket.io (Node) / a JS realtime stack | stay in the JVM/Spring stack; no polyglot ops | smaller community, fewer client SDKs |
| Raw Netty / hand-rolled | Spring Boot auto-config, MVC, crypto, metrics, cluster SPI for free | less control than hand-rolling everything |
| An IM SaaS (Agora/Sendbird/etc.) | self-hosted, no per-MAU cost, full data control | not turnkey; you operate Redis + nodes; ≤~10-node ceiling |

**Never claim:** unlimited fan-out, "breaks the ceiling" without the interest-partition caveat, or feature
parity with a dedicated IM platform. The site's "Honest scope" section is the template — reuse that language.

### 3.1 CN self-hosted IM comparison (ready-made block for 中文 comparison content)

Research (2026-08-03): the CN self-hosted Java IM space has two flawed incumbents and one aggressive
newcomer. Position netty-spring as **the Apache-2.0 IM *foundation* for Java/Spring teams building their
own IM with 自主可控 data** — never as an OpenIM replacement.

| Option | License | Deploy weight | Where it wins | Where netty-spring loses to it (mandatory) |
|---|---|---|---|---|
| OpenIM (16.5k★) | Apache-2.0 | ~14 containers (Mongo/Kafka/etcd/MinIO…) | Full IM product: SDKs for 6+ platforms, admin console, push | Client SDKs, admin console, turnkey IM surface — netty-spring ships none |
| 野火 WildFireChat (8.5k★) | **CC BY-ND — non-OSI, no derivatives** | Single Java app | Mature IM feature set; clustering in paid pro edition | Feature breadth (recall/receipts/group-mgmt/push); their clustering is paywalled while ours is built-in — but their *product* is more complete |
| WuKongIM (4.9k★) | Apache-2.0 | Go, Compose multi-node | Owns "simple self-hosted IM" with client SDKs (Android/iOS/JS/Flutter/UniApp/HarmonyOS) | Client SDKs again; it owns this positioning — do not attack head-on |

Pitch: foundation + honest ceilings + one Spring app + one Redis. Anti-pitch (say it yourself): no client
SDKs, no admin console, recall/receipts/history not shipped (documented, gated on adopter demand).

## 4. Channels (ranked by fit, with the concrete ask)

1. **Maven Central + GitHub SEO (passive, do first).** GitHub topics: `spring-boot`, `netty`, `websocket`,
   `spring-boot-starter`, `im`, `realtime`, `cluster`. Crisp repo description + the site link in About.
   Most discovery for a library is search, not posts.
2. **dev.to / Medium (EN) + 掘金/juejin (中文): a tutorial, not an ad.** "Add clustered WebSocket to a Spring
   Boot app in 20 minutes" — single-node first, then one flag for cluster, then `sendToUser`. End with the
   honest-scope section. Tutorials age well and rank.
3. **Reddit r/java, r/SpringBoot.** Share the *tutorial*, not the repo. Title: "I built a Spring Boot starter
   for clustered WebSocket — and documented exactly where it stops scaling." The honesty angle is the hook on
   Reddit (they punish hype).
4. **Hacker News (Show HN) — once, with the strongest asset.** "Show HN: netty-spring — Spring Boot WebSocket
   that scales across nodes, with its limits documented." Post the *site*, be in the thread to answer. HN
   rewards candor and punishes marketing; the honest-scope page is your edge. Only fire this once — make it count.
5. **CSDN (中文, Baidu SEO) + 掘金 + 思否/SegmentFault + Spring/Java WeChat groups.** Same tutorial,
   localized. Note: V2EX has been GFW-blocked since 2021-04 — do not plan it as a mainland funnel
   (2026-08 research). Juejin search volume is unmeasurable (anti-bot) — treat CN sizing as directional.
6. **Spring community (spring.io blog guest? Awesome-Spring lists, Awesome-Netty).** Submit to curated lists.

## 5. Launch sequence

- **Week 0 (assets):** site live ✓, README polished ✓, GitHub Release ✓, **multi-node proof ALREADY
  SHIPPED** (`docker-demo/`: 2 nodes + nginx LB + Redis + headless smoke oracle, in every tag since
  v1.9.0 — but referenced nowhere; link it from README/release-notes/api-guide), **chat demo ALREADY
  in the demo module** (`/chat` + crypto/auth profiles), **one architecture diagram** (single-node vs
  cluster vs mesh — still to build), GitHub topics + description set.
- **Week 1 (seed):** publish the EN tutorial (dev.to) + 中文 (掘金). Submit to Awesome lists. Set Central
  artifact READMEs.
- **Week 2 (amplify):** Reddit r/java + r/SpringBoot with the tutorial. V2EX/掘金 follow-up.
- **Week 3 (peak):** Show HN with the site, when you can be online ~4h to answer. Cross-link the demo.
- **Ongoing:** answer every issue/question fast (responsiveness is the strongest signal for a solo-maintained
  library); write a short "1.10.0 mesh deep-dive" post for the curious.

## 6. Ready-to-use titles / messaging

- **Tutorial (EN):** "Clustered WebSocket on Spring Boot in 20 minutes — and where it stops scaling"
- **Tutorial (中文):** "用一个 starter 给 Spring Boot 加上集群 WebSocket（以及它的诚实边界）"
- **Reddit:** "Spring Boot starter for clustered WebSocket — single-node by default, opt-in cluster, limits documented"
- **Show HN:** "Show HN: netty-spring — Spring Boot + Netty WebSocket with an honestly-scoped IM cluster"
- **Elevator (always):** "WebSocket + HTTP on Spring Boot via Netty; flip one flag for cross-node rooms,
  `sendToUser`, presence, and a node-to-node mesh. ≤~10 nodes, at-most-once broadcast — all in the docs."

## 7. Assets checklist

- [x] Landing site (EN + 中文) — `netty-spring-site/` (live at www.mysplitter.com/netty-spring-site/)
- [x] GitHub Release with notes — `v1.10.0`
- [x] Honest-scope section (site + docs)
- [x] **Multi-node Docker proof** — `docker-demo/` shipped since v1.9.0; **invisible in docs — link it** (Phase 1)
- [ ] **Standalone chat demo repo** — package the existing demo module + docker-demo, don't rebuild (Phase 2)
- [ ] **Architecture diagram** (single-node → Redis cluster → mesh; one image)
- [ ] EN tutorial (dev.to) + 中文 tutorial (掘金/CSDN)
- [ ] GitHub topics + repo "About" description + site link
- [ ] Awesome-Spring / Awesome-Netty list submissions
- [ ] A short FAQ ("Do I need Redis? When NOT to use the cluster? vs STOMP?")
- [ ] SO answers: #42901062 (WebSocket without STOMP/SockJS + broker/routing, 0 answers) and
      #26853745 (WebSocket in a Tomcat cluster, 21k views) — drafts in `docs/so-answer-drafts.md`

## 8. Metrics to watch

GitHub stars/forks/traffic (Insights), Maven Central download trend, issue response time (keep < 24h),
referral sources (which channel converts), and **quality of issues** (real adopters file specific bugs).

## 9. The one rule

**Don't oversell.** The project's whole identity is documenting its limits. A promotion that hypes the
ceiling-break would contradict the README and the site, and the audiences that matter (HN, r/java) would
notice. Lead with the honest, simple win — the candor *is* the marketing.
