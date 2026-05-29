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
 * When enabled, a {@link ClusterMessageSender} replaces the default
 * {@link com.github.berrywang1996.netty.spring.web.websocket.context.DefaultMessageSender}.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public class ClusterProperties {

    /** Master switch. Default false — cluster is opt-in only. */
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

    // ---- Failure mode ----

    /** What to do when Redis is lost. Default: degrade-to-local (keep local sessions alive). */
    private OnRedisLoss onRedisLoss = OnRedisLoss.DEGRADE_TO_LOCAL;

    /** Grace period in seconds before transitioning to DEGRADED after Redis loss. Default 60. */
    private long redisLossGracePeriodSeconds = 60;

    /** Max jitter in seconds for reconnect storm prevention. Default 10. */
    private long reconnectJitterMaxSeconds = 10;

    // ---- Performance / capacity ----

    /** Number of Pub/Sub connections per node (parallel decode). Default 2. */
    private int pubsubConnections = 2;

    /** Pipeline batch size for session registry writes. Default 64. */
    private int publishBatchSize = 64;

    /** Pipeline flush interval in ms. Default 10. */
    private long publishFlushIntervalMs = 10;

    /** Max subscribed channels (hard limit). Default 1024. */
    private int maxSubscribedChannels = 1024;

    /** Subscription hold duration in seconds after last local session leaves a URI. Default 60. */
    private long subscriptionHoldDurationSeconds = 60;

    /** Local sessionId→nodeId cache TTL in ms for unicast hot path. Default 5000. */
    private long registryReadCacheTtlMs = 5000;

    /** Max message size in bytes. Default 1MB. */
    private int messageMaxSizeBytes = 1048576;

    /** Session registry write rate limit in ops/s/node (reconnect storm throttle). Default 1000. */
    private int sessionRegistryWriteRate = 1000;

    // ---- Delivery ----

    /** What to do on publish failure. Default: log. */
    private OnPublishFailure onPublishFailure = OnPublishFailure.LOG;

    /** Redis Streams MAXLEN for reliable broadcast. Default 100000. */
    private long reliableStreamMaxLen = 100000;

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

    public OnRedisLoss getOnRedisLoss() { return onRedisLoss; }
    public void setOnRedisLoss(OnRedisLoss v) { this.onRedisLoss = v; }

    public long getRedisLossGracePeriodSeconds() { return redisLossGracePeriodSeconds; }
    public void setRedisLossGracePeriodSeconds(long v) { this.redisLossGracePeriodSeconds = v; }

    public long getReconnectJitterMaxSeconds() { return reconnectJitterMaxSeconds; }
    public void setReconnectJitterMaxSeconds(long v) { this.reconnectJitterMaxSeconds = v; }

    public int getPubsubConnections() { return pubsubConnections; }
    public void setPubsubConnections(int v) { this.pubsubConnections = v; }

    public int getPublishBatchSize() { return publishBatchSize; }
    public void setPublishBatchSize(int v) { this.publishBatchSize = v; }

    public long getPublishFlushIntervalMs() { return publishFlushIntervalMs; }
    public void setPublishFlushIntervalMs(long v) { this.publishFlushIntervalMs = v; }

    public int getMaxSubscribedChannels() { return maxSubscribedChannels; }
    public void setMaxSubscribedChannels(int v) { this.maxSubscribedChannels = v; }

    public long getSubscriptionHoldDurationSeconds() { return subscriptionHoldDurationSeconds; }
    public void setSubscriptionHoldDurationSeconds(long v) { this.subscriptionHoldDurationSeconds = v; }

    public long getRegistryReadCacheTtlMs() { return registryReadCacheTtlMs; }
    public void setRegistryReadCacheTtlMs(long v) { this.registryReadCacheTtlMs = v; }

    public int getMessageMaxSizeBytes() { return messageMaxSizeBytes; }
    public void setMessageMaxSizeBytes(int v) { this.messageMaxSizeBytes = v; }

    public int getSessionRegistryWriteRate() { return sessionRegistryWriteRate; }
    public void setSessionRegistryWriteRate(int v) { this.sessionRegistryWriteRate = v; }

    public OnPublishFailure getOnPublishFailure() { return onPublishFailure; }
    public void setOnPublishFailure(OnPublishFailure v) { this.onPublishFailure = v; }

    public long getReliableStreamMaxLen() { return reliableStreamMaxLen; }
    public void setReliableStreamMaxLen(long v) { this.reliableStreamMaxLen = v; }

    // ---- Nested classes ----

    public static class Redis {
        /** Redis mode. Default standalone. */
        private RedisMode mode = RedisMode.STANDALONE;

        /** Redis connection URI. Default redis://localhost:6379. */
        private String uri = "redis://localhost:6379";

        /** Whether to use sharded pub/sub in cluster mode. auto = detect. */
        private ShardedPubSub shardedPubsub = ShardedPubSub.AUTO;

        public RedisMode getMode() { return mode; }
        public void setMode(RedisMode mode) { this.mode = mode; }

        public String getUri() { return uri; }
        public void setUri(String uri) { this.uri = uri; }

        public ShardedPubSub getShardedPubsub() { return shardedPubsub; }
        public void setShardedPubsub(ShardedPubSub v) { this.shardedPubsub = v; }
    }

    public enum RedisMode {
        STANDALONE, SENTINEL, CLUSTER
    }

    public enum ShardedPubSub {
        /** Auto-detect: use sharded pub/sub if mode=CLUSTER and Lettuce ≥6.5.5. */
        AUTO,
        /** Force sharded pub/sub on. */
        ON,
        /** Force sharded pub/sub off (classic SUBSCRIBE/PUBLISH). */
        OFF
    }

    public enum OnRedisLoss {
        /** Keep local sessions alive, pause cross-node traffic. Default. */
        DEGRADE_TO_LOCAL,
        /** Close all local sessions when Redis is lost. */
        CLOSE_ALL
    }

    public enum OnPublishFailure {
        /** Silently drop the message. */
        DROP,
        /** Log a warning (default). */
        LOG,
        /** Invoke a user-provided callback. */
        CALLBACK
    }
}
