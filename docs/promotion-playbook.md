# netty-spring — Promotion Playbook

Updated 2026-10-23. This is the single promotion plan; product scope and sequencing live in the
[development plan](development-plan.md). The useful distribution decisions from the August research
are incorporated here. Historical star counts, competitor rankings, and speculative adoption figures
are not current evidence and must be checked again before any public comparison.

## Positioning and audience

**One-liner:** Spring Boot + Netty for HTTP MVC and plain WebSocket — single-node by default, with
opt-in cross-node rooms, user delivery, presence, and mesh transport.

Lead with the small, reproducible setup: add a starter, write a handler, then enable clustering when
needed. The audience is Java/Spring teams adding chat, notifications, or live dashboards and teams
building their own server-side IM foundation. netty-spring does not provide client SDKs, an admin
console, or a turnkey IM product.

Every tutorial and launch post must include the operating limits: roughly ≤10 nodes for active
Redis Pub/Sub broadcast, at-most-once cross-node delivery by default, and mesh fan-out reduction
only when live audiences are distributed across a subset of nodes. Global or high-population topics
under random load balancing can still reach the whole fleet. Published benchmark numbers must link
to the method and reproduction commands in the README.

## Release gates and sequence

### Before 2.0.0

1. Finish the 1.10.1 patch release. Its publication status and verification evidence live only in
   [the release notes](release-notes-1.10.1.md). Cluster tutorials and Answer 2 must not be published
   as usable examples until the 1.10.1 artifacts are available from Maven Central.
2. Keep existing proof assets easy to find from the README, API guide, FAQ, and site. Reuse the
   checked-in chat application and Docker demo instead of building another demo from scratch.
3. Review repository topics (`spring-boot`, `netty`, `websocket`, `spring-boot-starter`, `realtime`,
   `cluster`), About description, and site link. Confirm remote state before marking these done.
4. Keep 1.x platform limits explicit. Prepare content while the main development effort completes
   the Spring Boot 4.1 migration described in the development plan.

### At the 2.0.0 GA window

1. Update both tutorials and dependency examples to the released Boot 4.1 line, then reproduce them.
2. Publish the English tutorial on dev.to and the Chinese tutorial on 掘金/CSDN; link the runnable demo.
3. Submit relevant Awesome list entries and share the tutorial in Java/Spring communities.
4. Schedule Reddit and a single Show HN launch when the maintainer can respond to questions.
5. If needed, package the existing chat app and Docker setup into a standalone demo repository.
   A mesh measurements post must show both effective audience partitioning and the saturation case.

This sequence is event-driven, not a dated weekly campaign. Creating a draft or asset does not mean
that it has been published, submitted, or accepted externally.

## Assets and publication checklist

| Asset | Local state | External action |
|---|---|---|
| Bilingual site source | Not present in this checkout; locate the separate site project | Verify the deployed site before each launch |
| Multi-node proof | Present: [Docker demo](../docker-demo/README.md); linked from README/API guide | Re-run against the release being promoted |
| Chat application | Present: [demo module](../demo-netty-web-spring-boot-starter/) | Optional standalone packaging after 2.0.0 |
| Architecture diagram | Present: [architecture.svg](assets/architecture.svg) | Keep platform labels aligned with the release |
| FAQ | Present: [FAQ](faq.md) | Review with each release |
| English / Chinese tutorials | Drafts: [EN](tutorials/tutorial-en-clustered-websocket.md), [中文](tutorials/tutorial-zh-clustered-websocket.md) | Publication not recorded; obey the release gate |
| Stack Overflow answers | [Two drafts](so-answer-drafts.md) and [question scouting](so-question-scouting.md) | Publication not recorded; disclose authorship |
| Awesome list submissions | [Submission material](awesome-list-submissions.md) | Verify each submission/acceptance separately |
| Repository topics / About | Requires remote verification | Apply and record only after checking |

## Comparisons and Chinese content

Keep comparisons focused on protocol, integration, deployment responsibilities, and capabilities.
Check current upstream documentation and licenses before publishing a named comparison; omit dated
star counts, unanswered-issue counts, and claims that another project is abandoned.

| Reader's need | Comparison angle | Limitation to state |
|---|---|---|
| Spring plain WebSocket with explicit routing | Compare annotated handlers and `MessageSender` with Spring's raw WebSocket / STOMP choices | A team retaining STOMP may be better served by a broker relay |
| Socket.IO client compatibility | Put the wire protocol first in any netty-socketio / socketio4j comparison | Native WebSocket is not a Socket.IO-compatible replacement |
| Single-node annotated Netty endpoints | Compare programming models with YeautyYE's starter | Existing annotations and handler code require adaptation |
| Full self-hosted IM (OpenIM / WildFireChat / WuKongIM) | Explain a Spring-native foundation that the team extends | No client SDKs, admin console, message recall, receipts, or history API |

For Chinese content, start with the existing tutorial and this comparison scope on 掘金/CSDN.
Only consider additional channels or a separate distribution after attributable reader or adopter
feedback. Do not create a separate “Chinese private-deployment suite” as part of the migration.

## Titles and follow-up

- **English tutorial:** “Clustered WebSocket on Spring Boot — setup, delivery semantics, and limits”
- **中文教程：**“用一个 starter 给 Spring Boot 加上集群 WebSocket，以及它的适用边界”
- **Show HN:** “netty-spring: Spring Boot + Netty WebSocket with documented cluster limits”

Review referral traffic, artifact downloads where available, concrete adopter issues, and response
time. Compare those signals over a campaign rather than using a star count as proof of demand.
After 4–6 weeks, expand a channel only if it brings attributable interest; after a quarter without
referrals, reconsider the promotion effort. Aim to acknowledge substantive questions within a day.

Feature requests such as recall, receipts, history, or tenant quotas need real adopter use cases
before implementation. They remain post-2.0.0 candidates in the development plan; this playbook
does not assign them a release or add them to the Boot migration.
