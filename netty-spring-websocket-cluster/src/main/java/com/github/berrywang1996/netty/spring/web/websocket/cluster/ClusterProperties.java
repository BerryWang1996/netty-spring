/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.websocket.cluster;

/**
 * Configuration properties for WebSocket cluster support.
 * Bound to {@code server.netty.websocket.cluster.*}.
 *
 * <p>Default is {@code enable=false} — single-node mode with zero cluster overhead.
 * When enabled, a {@link ClusterMessageSender} becomes the {@code @Primary}
 * {@link com.github.berrywang1996.netty.spring.web.websocket.context.MessageSender},
 * wrapping the local single-node sender.
 *
 * <p><b>Scope note:</b> this class exposes only properties that have an observable effect in the
 * current implementation. Purely-roadmap knobs described in {@code docs/cluster-design.md}
 * (write pipelining, sharded pub/sub, NATS broker, etc.) are intentionally NOT exposed here until
 * their underlying feature ships — config that does nothing is worse than no config.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public class ClusterProperties {

    /** Master switch. Default false — cluster is opt-in only.
     *  (Read by {@code @ConditionalOnProperty}; the cluster auto-configuration only activates when true.) */
    private boolean enable = false;

    /** Unique node identifier. Null or empty = auto-generated UUID. */
    private String nodeId;

    // ---- Redis connection ----

    private Redis redis = new Redis();

    // ---- Heartbeat / failure detection ----

    /** Heartbeat interval in seconds (how often this node writes its heartbeat). Default 3. */
    private long heartbeatIntervalSeconds = 3;

    /** Heartbeat timeout in seconds (how long before a missing heartbeat = dead node). Default 10. */
    private long heartbeatTimeoutSeconds = 10;

    /** Reconciliation interval in seconds (slow-path backstop for missed keyspace notifications). Default 15. */
    private long reconciliationIntervalSeconds = 15;

    /**
     * Graceful-shutdown drain window in seconds. On shutdown the node transitions to DRAINING and waits
     * up to this long for in-flight cross-node deliveries to settle before it deregisters and goes LEFT.
     * Default {@code 0} = no wait (instant deregister, matching pre-1.9.0 behavior). Set a positive value
     * to opt into a bounded grace window (note: this is a fixed bounded wait, not a session-count drain).
     */
    private long drainTimeoutSeconds = 0;

    /** Max jitter in seconds applied before a DEGRADED→RESYNC re-registration, to avoid
     *  reconnect storms when many nodes recover simultaneously. Default 10. */
    private long reconnectJitterMaxSeconds = 10;

    /** Grace period in ms before a transport loss degrades the NODE state machine (debounce). A blip
     *  shorter than this won't flap the node or trigger {@code on-redis-loss=close-all}. The broker's
     *  own {@code state()} still flips immediately (truthful health + fast-fail of in-flight publishes).
     *  0 = instant degrade (exact 1.8.0 behavior). Default 5000. */
    private long redisLossGracePeriodMs = 5000;

    // ---- Failure handling ----

    /** What to do when Redis (cluster transport) is lost. Default {@code DEGRADE_TO_LOCAL}:
     *  keep local sessions alive and continue local fan-out, pausing only cross-node traffic. */
    private OnRedisLoss onRedisLoss = OnRedisLoss.DEGRADE_TO_LOCAL;

    /** What to do when a cluster publish/unicast fails. Default {@code LOG}. */
    private OnPublishFailure onPublishFailure = OnPublishFailure.LOG;

    // ---- Capacity / safety ----

    /** Local sessionId→nodeId cache TTL in ms for the unicast hot path. Default 5000. */
    private long registryReadCacheTtlMs = 5000;

    /** Max entries in the local sessionId→nodeId read cache (unicast hot path). The TTL governs only
     *  reuse, not eviction, so without a hard cap a node that unicasts to many distinct LIVE remote
     *  sessions would grow this map without bound. Bounded LRU eviction caps the footprint. {@code <= 0}
     *  = unbounded (legacy behavior — not recommended). Default 100000. */
    private int registryReadCacheMaxSize = 100_000;

    /** Redis command timeout in ms for the cluster control plane. Bounds how long any single
     *  Redis operation (incl. the unicast hot-path registry lookup) can block when Redis is
     *  unreachable — much lower than Lettuce's 60s default. Default 2000. */
    private long commandTimeoutMs = 2000;

    /** Number of Redis Pub/Sub SUBSCRIBE connections to spread inbound decode across. {@code 1}
     *  (default) = single connection, byte-identical to pre-1.9.x behavior. 2–4 is recommended ONLY
     *  when a node approaches the single Lettuce pub/sub connection decode ceiling (~80k msg/s — see
     *  docs/cluster-design.md). Clamped to {@code [1, 16]}. Redis-Pub/Sub-specific (no effect on
     *  other transports). */
    private int pubsubConnections = 1;

    /** Max serialized cluster message size in bytes. Messages larger than this are not
     *  published to the cluster (local delivery is unaffected); handled per {@link #onPublishFailure}.
     *  Default 1 MiB. */
    private int messageMaxSizeBytes = 1048576;

    /** Max sustained session register+deregister writes per second per node. Absorbs reconnect storms
     *  by coalescing+throttling registry writes (never dropping a register). Under this rate, writes
     *  pass straight through with no added latency. 0 = unlimited (pure pass-through). Default 1000. */
    private long sessionRegistryWriteRate = 1000;

    /** Opt-in reliable (at-least-once) broadcast over Redis Streams. Disabled by default. */
    private Reliable reliable = new Reliable();

    /** Opt-in HMAC authentication of cross-node envelopes. Disabled by default. */
    private Auth auth = new Auth();

    /** NATS broker (ADR-001 scaling tier). When {@code servers} is non-empty, replaces the Redis broker
     *  (transport only — registry/heartbeat stay on Redis). Default empty = Redis broker. */
    private Nats nats = new Nats();

    /** Opt-in W3C TraceContext (traceparent) cross-node propagation + MDC restore. Disabled by default. */
    private TracePropagation tracePropagation = new TracePropagation();

    /** Opt-in room-scoped routing (per-room node-targeted delivery). Disabled by default. */
    private Room room = new Room();

    /** Opt-in user-addressed delivery + per-user offline queue. Disabled by default. */
    private Offline offline = new Offline();

    /** Opt-in multi-device aggregate presence + presence-change events (1.10.0-RC3). Disabled by default. */
    private Presence presence = new Presence();

    // ---- Getters / Setters ----

    public boolean isEnable() { return enable; }
    public void setEnable(boolean enable) { this.enable = enable; }

    public String getNodeId() { return nodeId; }
    public void setNodeId(String nodeId) { this.nodeId = nodeId; }

    public Redis getRedis() { return redis; }
    public void setRedis(Redis redis) { this.redis = redis; }

    public long getHeartbeatIntervalSeconds() { return heartbeatIntervalSeconds; }
    public void setHeartbeatIntervalSeconds(long v) { this.heartbeatIntervalSeconds = v; }

    public long getHeartbeatTimeoutSeconds() { return heartbeatTimeoutSeconds; }
    public void setHeartbeatTimeoutSeconds(long v) { this.heartbeatTimeoutSeconds = v; }

    public long getReconciliationIntervalSeconds() { return reconciliationIntervalSeconds; }
    public void setReconciliationIntervalSeconds(long v) { this.reconciliationIntervalSeconds = v; }

    public long getDrainTimeoutSeconds() { return drainTimeoutSeconds; }
    public void setDrainTimeoutSeconds(long v) { this.drainTimeoutSeconds = v; }

    public long getReconnectJitterMaxSeconds() { return reconnectJitterMaxSeconds; }
    public void setReconnectJitterMaxSeconds(long v) { this.reconnectJitterMaxSeconds = v; }

    public long getRedisLossGracePeriodMs() { return redisLossGracePeriodMs; }
    public void setRedisLossGracePeriodMs(long v) { this.redisLossGracePeriodMs = v; }

    public OnRedisLoss getOnRedisLoss() { return onRedisLoss; }
    public void setOnRedisLoss(OnRedisLoss v) { this.onRedisLoss = v; }

    public OnPublishFailure getOnPublishFailure() { return onPublishFailure; }
    public void setOnPublishFailure(OnPublishFailure v) { this.onPublishFailure = v; }

    public long getRegistryReadCacheTtlMs() { return registryReadCacheTtlMs; }
    public void setRegistryReadCacheTtlMs(long v) { this.registryReadCacheTtlMs = v; }

    public int getRegistryReadCacheMaxSize() { return registryReadCacheMaxSize; }
    public void setRegistryReadCacheMaxSize(int v) { this.registryReadCacheMaxSize = v; }

    public long getCommandTimeoutMs() { return commandTimeoutMs; }
    public void setCommandTimeoutMs(long v) { this.commandTimeoutMs = v; }

    public int getPubsubConnections() { return pubsubConnections; }
    public void setPubsubConnections(int v) { this.pubsubConnections = v; }

    public int getMessageMaxSizeBytes() { return messageMaxSizeBytes; }
    public void setMessageMaxSizeBytes(int v) { this.messageMaxSizeBytes = v; }

    public long getSessionRegistryWriteRate() { return sessionRegistryWriteRate; }
    public void setSessionRegistryWriteRate(long v) { this.sessionRegistryWriteRate = v; }

    public Reliable getReliable() { return reliable; }
    public void setReliable(Reliable reliable) { this.reliable = reliable; }

    public Auth getAuth() { return auth; }
    public void setAuth(Auth auth) { this.auth = auth; }

    public Nats getNats() { return nats; }
    public void setNats(Nats nats) { this.nats = nats; }

    public TracePropagation getTracePropagation() { return tracePropagation; }
    public void setTracePropagation(TracePropagation v) { this.tracePropagation = v; }

    public Room getRoom() { return room; }
    public void setRoom(Room room) { this.room = room; }

    public Offline getOffline() { return offline; }
    public void setOffline(Offline offline) { this.offline = offline; }

    public Presence getPresence() { return presence; }
    public void setPresence(Presence presence) { this.presence = presence; }

    // ---- Nested classes ----

    /**
     * Redis connection settings. The topology (standalone vs sentinel) is selected by the
     * {@link #uri} scheme, which Lettuce auto-detects:
     * <ul>
     *   <li>{@code redis://host:port} — standalone</li>
     *   <li>{@code redis-sentinel://host:port/?sentinelMasterId=...} — sentinel</li>
     * </ul>
     * Redis <b>Cluster</b> (which requires a different client type) is now a first-class transport:
     * set {@link #clusterNodes} ({@code host:port,host:port,...}) and the auto-configuration selects a
     * {@code RedisClusterClient} + the {@code RedisClusterMode*} broker/registry/heartbeat/reaper instead
     * of the standalone/sentinel {@link #uri}. The two transports are mutually exclusive.
     */
    public static class Redis {
        /** Redis connection URI. Default {@code redis://localhost:6379}. */
        private String uri = "redis://localhost:6379";

        /** Comma-separated Redis Cluster seed nodes ({@code host:port,host:port,...}). When non-empty,
         *  the Redis <b>Cluster</b> transport (RedisClusterClient + RedisClusterMode* impls) is used
         *  INSTEAD of the standalone/sentinel {@link #uri}. Empty/absent (default) = standalone via uri.
         *  Auth/TLS is not expressible in this host:port list in 1.9.0 — for a secured cluster supply your
         *  own {@code RedisClusterClient} bean (it is {@code @ConditionalOnMissingBean}). Default empty. */
        private String clusterNodes;

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }

        public String getClusterNodes() { return clusterNodes; }
        public void setClusterNodes(String clusterNodes) { this.clusterNodes = clusterNodes; }
    }

    /**
     * Reliable (at-least-once) broadcast settings. Off by default — when {@code enable=false} there
     * are no consumer threads, no extra Redis connection, and {@code reliableBroadcast()} throws.
     */
    public static class Reliable {
        /** Master gate for reliable broadcast. Default false. */
        private boolean enable = false;
        /** Per-URI stream MAXLEN (approx) — the at-least-once retention window. Default 10000. */
        private int streamMaxLen = 10000;
        /** XREADGROUP BLOCK timeout (ms) for the consume loop. Default 2000. */
        private long pollBlockMs = 2000;
        /** XREADGROUP COUNT per read. Default 64. */
        private int pollCount = 64;
        /** Per-URI ring size of recently-acked entry ids (in-process redelivery dedup). Default 1024. */
        private int dedupWindow = 1024;
        /** Idle window (ms) a dead node's consumer group must be inactive before reconciliation may destroy
         *  it (on heartbeat-expiry). A node's id is stable, so a crashed node that RESTARTS reuses its group
         *  {@code g:{nodeId}}; destroying it too eagerly would wipe the retained offset+PEL and the restarted
         *  node would skip its backlog (data loss). This window keeps a recently-active group intact past any
         *  realistic crash-restart gap. {@code <= 0} = never destroy on expiry (pure retain; rely on MAXLEN
         *  trimming). Default 3600000 (1h). */
        private long groupDestroyIdleMs = 3600000L;

        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
        public int getStreamMaxLen() { return streamMaxLen; }
        public void setStreamMaxLen(int v) { this.streamMaxLen = v; }
        public long getPollBlockMs() { return pollBlockMs; }
        public void setPollBlockMs(long v) { this.pollBlockMs = v; }
        public int getPollCount() { return pollCount; }
        public void setPollCount(int v) { this.pollCount = v; }
        public int getDedupWindow() { return dedupWindow; }
        public void setDedupWindow(int v) { this.dedupWindow = v; }
        public long getGroupDestroyIdleMs() { return groupDestroyIdleMs; }
        public void setGroupDestroyIdleMs(long v) { this.groupDestroyIdleMs = v; }
    }

    /**
     * HMAC envelope authentication. Off by default. When {@code enable=true}, every cross-node envelope
     * is signed (HMAC-SHA256) and inbound envelopes with a missing/invalid tag are rejected.
     */
    public static class Auth {
        /** Master gate. Default false (no signing; pass-through). */
        private boolean enable = false;
        /** Shared HMAC secret (UTF-8). Required when enable=true. Externalize via ${ENV}; never hardcode. */
        private String secret;
        /** Migration: when true, accept UNSIGNED inbound (still signing outbound) — for rolling rollout.
         *  When false (default), reject unsigned inbound. */
        private boolean permissive = false;

        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public boolean isPermissive() { return permissive; }
        public void setPermissive(boolean permissive) { this.permissive = permissive; }
    }

    /**
     * W3C TraceContext propagation. Off by default. When {@code enable=true}, the current
     * traceparent is carried in the envelope and restored into MDC on the receiving node so
     * cross-node deliveries log with the originating trace id.
     */
    public static class TracePropagation {
        /** Master gate. Default false. */
        private boolean enable = false;

        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
    }

    /**
     * Room-scoped routing (per-room node-targeted delivery). Off by default — when {@code enable=false}
     * there are no room beans, no room subscriptions, and no ROOM_BROADCAST envelopes are produced, so
     * runtime <b>behavior</b> is identical to 1.9.0. (Note: the envelope <b>wire</b> is globally v2 since
     * 1.10.0 — 9 fields incl. an empty room field — so it is not byte-for-byte identical to 1.9.0's v1/8-field
     * wire; a 1.9.0 node safely discards a v2 wire on the version gate, see EnvelopeRollingUpgradeTest.)
     * When enabled, a {@code ClusterRoomRegistry} bean is wired and {@code roomMessage(uri, room, msg)}
     * targets only the nodes hosting members of the room.
     */
    public static class Room {
        /** Master gate. Default false. */
        private boolean enable = false;
        /** Local cache TTL (ms) for the {@code nodesForRoom} node-set on the room send hot path (mirrors
         *  {@link #registryReadCacheTtlMs}). A stale node-set self-heals within this window. Default 5000. */
        private long nodeSetCacheTtlMs = 5000;

        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
        public long getNodeSetCacheTtlMs() { return nodeSetCacheTtlMs; }
        public void setNodeSetCacheTtlMs(long nodeSetCacheTtlMs) { this.nodeSetCacheTtlMs = nodeSetCacheTtlMs; }
    }

    /**
     * User-addressed delivery + per-user offline queue (1.10.0-RC2). Off by default — when
     * {@code enable=false} there are no offline beans, the {@code UserIdResolver} is never created, and the
     * cluster session hook resolves nothing + passes {@code emptyMap()} to register exactly as RC1
     * (byte-identical: no userId resolution, no presence binding, no offline queue). When enabled, a
     * {@code UserIdResolver} (default {@code HandshakeUserIdResolver}), {@code UserRegistry} (default
     * {@code RedisUserRegistry}) and {@code OfflineQueueStore} (default {@code RedisOfflineQueueStore}) are
     * wired, and {@code sendToUser(userId, msg)} delivers to an online user or enqueues for backfill on
     * reconnect.
     *
     * <p><b>SECURITY:</b> the default {@code HandshakeUserIdResolver} trusts a handshake query-param/header
     * VERBATIM and is <b>convenience/testing only</b>. Production IM MUST supply its own
     * {@code UserIdResolver} bean that derives the userId from the session's AUTHENTICATED principal — a wrong
     * identity is cross-user data exposure. See {@code UserIdResolver}'s SECURITY CONTRACT javadoc and
     * {@code docs/cluster-design.md} §Security.
     */
    public static class Offline {
        /** Master gate. Default false. */
        private boolean enable = false;
        /** Where {@code HandshakeUserIdResolver} reads the userId ({@code query:<name>} / {@code header:<name>}). */
        private String userIdSource = "query:userId";
        /** Per-user Redis Stream {@code MAXLEN ~} — the offline retention bound (oldest trimmed past this). */
        private int maxMessagesPerUser = 1000;
        /** Per-message age cap (seconds); lazily dropped on drain + bounded by stream trim. Default 7 days. */
        private long ttlSeconds = 604800;
        /** Max messages drained per connect (the rest drain on a subsequent connect). Default 100. */
        private int drainBatchSize = 100;
        /** Per-userId drain lock TTL ({@code SET NX PX}); auto-expires so a crashed drainer can't wedge the
         *  user's queue. Default 5000. */
        private long drainLockMs = 5000;

        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
        public String getUserIdSource() { return userIdSource; }
        public void setUserIdSource(String userIdSource) { this.userIdSource = userIdSource; }
        public int getMaxMessagesPerUser() { return maxMessagesPerUser; }
        public void setMaxMessagesPerUser(int maxMessagesPerUser) { this.maxMessagesPerUser = maxMessagesPerUser; }
        public long getTtlSeconds() { return ttlSeconds; }
        public void setTtlSeconds(long ttlSeconds) { this.ttlSeconds = ttlSeconds; }
        public int getDrainBatchSize() { return drainBatchSize; }
        public void setDrainBatchSize(int drainBatchSize) { this.drainBatchSize = drainBatchSize; }
        public long getDrainLockMs() { return drainLockMs; }
        public void setDrainLockMs(long drainLockMs) { this.drainLockMs = drainLockMs; }
    }

    /**
     * Multi-device aggregate presence settings (1.10.0-RC3). Requires the shared identity path
     * ({@code UserIdResolver} + {@code UserRegistry}), so enabling presence activates those beans even when
     * {@code offline.enable=false}. Standalone-Redis only (no Redis-Cluster client). Aggregate is multi-device
     * aware (ONLINE/AWAY/OFFLINE across all of a user's connections); per-device <i>addressing</i> is deferred.
     * Presence-change events are a <b>broadcast</b> on a reserved channel (same ~10-node Pub/Sub ceiling as
     * {@code topicMessage}); {@code getPresence} is ADVISORY (last-known state, not a liveness probe).
     */
    public static class Presence {
        /** Master gate. Default false. */
        private boolean enable = false;
        /** Whether aggregate transitions are broadcast cluster-wide as PRESENCE_CHANGE events. When false the
         *  local {@code PresenceChangeListener} still fires but no cross-node event is published (query-only
         *  deployments). Default true. */
        private boolean publishChanges = true;

        public boolean isEnable() { return enable; }
        public void setEnable(boolean enable) { this.enable = enable; }
        public boolean isPublishChanges() { return publishChanges; }
        public void setPublishChanges(boolean publishChanges) { this.publishChanges = publishChanges; }
    }

    /**
     * NATS broker (ADR-001 scaling tier) settings. When {@code servers} is non-empty, the
     * {@code NatsClusterBroker} replaces the Redis Pub/Sub broker (transport only — SessionRegistry and
     * heartbeat stay on Redis). Empty/absent (default) = the Redis broker. Requires {@code io.nats:jnats}
     * on the classpath.
     */
    public static class Nats {
        /** Comma-separated NATS server URLs ({@code nats://host:port,...}). Default empty. */
        private String servers;

        /** When {@code true} (and {@code servers} is set), the SessionRegistry/heartbeat/reaper run on NATS
         *  JetStream KV instead of Redis — a fully NATS-only deployment (no Redis). Requires a JetStream-enabled
         *  NATS server ({@code nats-server -js}). Default {@code false} (mixed: NATS broker + Redis registry). */
        private boolean registry = false;

        public String getServers() { return servers; }
        public void setServers(String servers) { this.servers = servers; }

        public boolean isRegistry() { return registry; }
        public void setRegistry(boolean registry) { this.registry = registry; }
    }

    /** Behavior when the cluster transport (Redis) is lost. */
    public enum OnRedisLoss {
        /** Keep local sessions alive, continue local fan-out, pause cross-node traffic. Default. */
        DEGRADE_TO_LOCAL,
        /** Close all local sessions when the transport is lost (fail-fast / load-shed). */
        CLOSE_ALL
    }

    /** Behavior when a cluster publish/unicast fails. */
    public enum OnPublishFailure {
        /** Silently drop the message. */
        DROP,
        /** Log a warning (default). */
        LOG
    }
}
