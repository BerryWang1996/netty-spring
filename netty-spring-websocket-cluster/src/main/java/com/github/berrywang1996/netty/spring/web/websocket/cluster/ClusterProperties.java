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
 * <p><b>Scope note (1.8.0):</b> this class exposes only properties that have an observable
 * effect in the 1.8.0 implementation. Additional knobs described in
 * {@code docs/cluster-design.md} (multi pub/sub connections, write pipelining, rate limiting,
 * reliable streams, Redis Cluster client, sharded pub/sub, etc.) are roadmap items and are
 * intentionally NOT exposed here until their underlying feature ships — config that does
 * nothing is worse than no config.
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

    /** Drain timeout in seconds (max time to wait for sessions to close during graceful shutdown). Default 60. */
    private long drainTimeoutSeconds = 60;

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

    /** Redis command timeout in ms for the cluster control plane. Bounds how long any single
     *  Redis operation (incl. the unicast hot-path registry lookup) can block when Redis is
     *  unreachable — much lower than Lettuce's 60s default. Default 2000. */
    private long commandTimeoutMs = 2000;

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

    public long getCommandTimeoutMs() { return commandTimeoutMs; }
    public void setCommandTimeoutMs(long v) { this.commandTimeoutMs = v; }

    public int getMessageMaxSizeBytes() { return messageMaxSizeBytes; }
    public void setMessageMaxSizeBytes(int v) { this.messageMaxSizeBytes = v; }

    public long getSessionRegistryWriteRate() { return sessionRegistryWriteRate; }
    public void setSessionRegistryWriteRate(long v) { this.sessionRegistryWriteRate = v; }

    public Reliable getReliable() { return reliable; }
    public void setReliable(Reliable reliable) { this.reliable = reliable; }

    // ---- Nested classes ----

    /**
     * Redis connection settings. The topology (standalone vs sentinel) is selected by the
     * URI scheme, which Lettuce auto-detects:
     * <ul>
     *   <li>{@code redis://host:port} — standalone</li>
     *   <li>{@code redis-sentinel://host:port/?sentinelMasterId=...} — sentinel</li>
     * </ul>
     * Redis Cluster (which requires a different client type) is a roadmap item; users running
     * Redis Cluster should provide their own {@code ClusterBroker}/{@code SessionRegistry} beans.
     */
    public static class Redis {
        /** Redis connection URI. Default {@code redis://localhost:6379}. */
        private String uri = "redis://localhost:6379";

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }
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
