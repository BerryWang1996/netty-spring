# Release Notes - 1.4.0

**Release Date:** 2026-05-27
**Release Type:** Feature (P7 Demo & Documentation Productization)

## Overview

`1.4.0` completes the P7 productization milestone, upgrading the project from "framework works" to "new users can quickly understand and integrate real business scenarios." This release adds a production-quality chat room demo, comprehensive API documentation, and expanded Starter integration test coverage.

## New Features

### Chat Room Demo

- **`ChatRoomController`**: Full-featured WebSocket chat room at `/ws/chat` with:
  - Nickname support via `?nickname=` query parameter (auto-generated fallback)
  - Real-time join/leave notifications broadcast to all users
  - Online user list synchronization
  - Broadcast messaging to all connected users
  - Private messaging via `/pm nickname text` command
  - `ConcurrentHashMap`-based session-to-nickname tracking
- **Chat Room UI**: Complete HTML/CSS/JavaScript chat interface served at `/chat` endpoint with:
  - Join overlay with nickname input
  - Sidebar showing online users (clickable for quick private message)
  - Connection status indicator
  - Message history with visual distinction for own messages, incoming, private, and system notifications
  - Responsive design (sidebar collapses on mobile)
- **Demo Home Page**: New "Chat Room" card added to the demo cockpit at `/` linking to `/chat`

### API Usage Guide

- **`docs/api-guide.md`**: 12-section comprehensive guide covering:
  1. Starter selection (which artifact for which scenario)
  2. Minimal HTTP MVC application
  3. HTTP + WebSocket combined application
  4. WebSocket-only application
  5. Complete WebSocket messaging API (`MessageSender`, `MessageSession`)
  6. Chat room pattern (multi-user real-world example)
  7. Handshake authentication (`WebSocketHandshakeInterceptor`, `ON_HANDSHAKE`)
  8. Application-layer encryption (AES-GCM, key provider, URI/session policies)
  9. Metrics & monitoring (built-in endpoints, Micrometer/Actuator)
  10. Full configuration reference (all `server.netty.*` properties)
  11. Annotation reference (MVC + WebSocket)
  12. Troubleshooting (common issues and solutions)

### Starter Integration Test Enhancement

- **`netty-web-spring-boot-starter`** tests increased from **6 to 12**:
  - Custom `MessageSender` bean overrides default `MessageSenderSupport`
  - Both MVC and WebSocket disabled still starts server
  - MVC disabled but WebSocket enabled registers WebSocket mappings
  - MVC and WebSocket controllers coexist (HTTP routes + WebSocket URIs)
  - Heartbeat and connection limit configuration binds correctly
  - Multiple WebSocket URIs registered from a single controller

## Compatibility

- **Backward compatible**: No breaking changes. All existing APIs, configurations, and behaviors are preserved.
- **Java**: Requires Java 17+
- **Spring Boot**: Compatible with Spring Boot 2.x (Spring Boot 3.x migration planned for 2.0.0)

## Upgrade Guide

Update your dependency version:

```xml
<dependency>
    <groupId>com.github.berrywang1996</groupId>
    <artifactId>netty-web-spring-boot-starter</artifactId>
    <version>1.4.0</version>
</dependency>
```

No code changes required. The new chat room demo is available at `/chat` when running the demo module.

## Version History

| Version | Milestone |
| --- | --- |
| 1.0.0 - 1.0.2 | P0-P3 Baseline, engineering governance |
| 1.1.0-RC2 | P4/P4.1 Starter consolidation, stability hardening |
| 1.2.0 - 1.2.3 | P5 WebSocket product capabilities, code quality |
| 1.3.0 | P6 Observability, Micrometer, handshake auth |
| 1.3.1 | Code quality deep cleanup |
| **1.4.0** | **P7 Demo & documentation productization** |
