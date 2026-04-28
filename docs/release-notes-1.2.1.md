# 1.2.1 发布说明

更新时间：2026-04-28

## 版本定位

`1.2.1` 是 WebSocket P5.x 功能/稳定性正式版，目标是把 `1.2.0` 已引入的应用层 crypto 能力补齐到更可灰度、更可演示、更容易观测的状态。

本版本不是企业安全正式版。Dependency-Check / Dependabot 漏洞 triage、标准握手鉴权扩展、完整 CORS/TLS 安全策略和企业级密钥分发方案继续放在后续安全专项中处理。

## 新增能力

- WebSocket crypto URI 策略：新增 `server.netty.websocket.crypto.include-uris` / `exclude-uris`，支持按 path、原始 URI、mapping URL 或 `*` 控制哪些 session 启用应用层 crypto。
- WebSocket crypto session 策略：新增 `MessageCryptoPolicy` 扩展点，允许业务在 URI 策略之后按 query、header、session id 或握手上下文继续决定是否启用 crypto。
- AES-GCM 密钥轮换基础路径：新 `key-id` 用于新消息加密，`MessageCryptoKeyProvider` 可在过渡期保留旧 key，用于解析旧 `kid` 的历史密文。
- 浏览器端 crypto demo：demo 新增 `/ws/crypto-demo` 页面，使用浏览器 WebCrypto 构造 AES-GCM envelope，便于验证 Network/WebSocket 面板看到密文、服务端 handler 拿到明文对象。
- WebSocket 轻量运行时统计：新增 `WebSocketRuntimeStats`，可通过 `NettyServerBootstrap#getWebSocketRuntimeStats()` 读取 mapping 数和活跃 session 数；开启内置管理端点后，`/netty/status` 同步输出 websocket 快照。

## 验收重点

- 全量 reactor `mvn test` 必须通过。
- 启用 AES-GCM 时必须提供 `MessageCryptoKeyProvider`，框架不硬编码密钥。
- URI include/exclude 与 `MessageCryptoPolicy` 必须同时影响出站加密、入站解密和未加密帧拒绝路径。
- `/ws/crypto-demo` 只用于联调与演示，内置 demo key 不能作为生产密钥分发方案。
- 应用层 crypto 不替代 TLS/WSS，也不承诺浏览器运行时完全不可见明文。

## 延期项

- P6：Micrometer/Actuator 指标、关闭原因维度、统一事件模型和更标准的指标命名。
- P7：完整聊天室/推送 demo、README 快速接入重写和更系统的示例体系。
- 企业安全版：依赖漏洞 triage、握手鉴权扩展、完整 CORS/TLS 策略、密钥分发和审计方案。

## 发版步骤

1. 确认工作树只包含本次发布相关变更。
2. 将根 `pom.xml` 从 `1.2.1-SNAPSHOT` 切到 `1.2.1`。
3. 运行全量 `mvn test`。
4. 创建发布提交。
5. 创建 tag：`v1.2.1`。
6. 推送分支和 tag。
