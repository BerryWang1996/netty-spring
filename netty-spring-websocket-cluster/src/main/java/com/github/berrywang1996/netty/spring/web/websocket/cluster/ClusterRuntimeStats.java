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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Cluster-specific runtime statistics maintained by {@link ClusterMessageSender}.
 *
 * <p>All counters are thread-safe (AtomicLong). They accumulate from the moment the
 * cluster sender starts until shutdown.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
public class ClusterRuntimeStats {

    /** Broadcast messages <b>handed to</b> the cluster broker for publish (attempted, not
     *  confirmed-delivered — the transport is async/at-most-once; async failures are counted
     *  in {@link #publishFailures} and logged). */
    final AtomicLong broadcastPublished = new AtomicLong();

    /** Broadcast messages received from other nodes and delivered locally. */
    final AtomicLong crossNodeBroadcastReceived = new AtomicLong();

    /** Self-delivered broadcasts suppressed (origin == local node). */
    final AtomicLong selfDeliveryDropped = new AtomicLong();

    /** Unicast messages sent to remote nodes via the broker. */
    final AtomicLong unicastSent = new AtomicLong();

    /** Cluster publishes that failed or were dropped (oversized payload / broker error). */
    final AtomicLong publishFailures = new AtomicLong();

    /** Cross-node broadcasts skipped because the node was not ACTIVE (DEGRADED/RESYNC/etc.).
     *  Local fan-out still happened; the cross-node copy was dropped (at-most-once). Makes
     *  degrade-to-local message loss visible/quantifiable to operators. */
    final AtomicLong broadcastsSkippedDegraded = new AtomicLong();

    /** Reliable broadcasts published (XADD issued). */
    final AtomicLong reliablePublished = new AtomicLong();
    /** Reliable broadcasts received from the stream and delivered locally. */
    final AtomicLong reliableReceived = new AtomicLong();

    /** Node lookup cache hits. */
    final AtomicLong cacheHits = new AtomicLong();

    /** Node lookup cache misses (fell through to registry). */
    final AtomicLong cacheMisses = new AtomicLong();

    // ---- Room-scoped routing (1.10.0) ----

    /** Room broadcasts published (per-room node-targeted sends). */
    final AtomicLong roomBroadcastPublished = new AtomicLong();

    /** Room broadcasts received from other nodes and locally delivered. */
    final AtomicLong roomBroadcastReceived = new AtomicLong();

    /** Room broadcasts received but with ZERO local members (membership churned in-flight = wasted
     *  delivery). The honest waste meter. */
    final AtomicLong roomFanoutStaleTarget = new AtomicLong();

    /** Number of room broadcasts for which fan-out targets were recorded (denominator for the average). */
    private final AtomicLong roomFanoutSampleCount = new AtomicLong();

    /** Sum of target-node counts across all room broadcasts (numerator for the average). The reduction
     *  meter: compare {@code roomFanoutTargetsTotal / sampleCount} to the cluster size. */
    private final AtomicLong roomFanoutTargetsTotal = new AtomicLong();

    /** Target nodes on the most recent room broadcast (a gauge-friendly latest sample). */
    private final AtomicLong roomFanoutTargetsLast = new AtomicLong();

    /** Records the number of nodes targeted by one room broadcast (the per-room fan-out reduction sample). */
    public void recordRoomFanoutTargets(int targetNodes) {
        roomFanoutSampleCount.incrementAndGet();
        roomFanoutTargetsTotal.addAndGet(targetNodes);
        roomFanoutTargetsLast.set(targetNodes);
    }

    /** Total local room memberships on this node (gauge). Incremented on a local joinRoom, decremented on
     *  leaveRoom / removeAllRoomsForSession. Floored at 0 (defensive against a double-leave). */
    private final AtomicLong roomLocalMemberships = new AtomicLong();

    /** Adjusts the local-room-membership gauge by {@code delta} (never below 0). */
    public void addRoomLocalMemberships(long delta) {
        roomLocalMemberships.updateAndGet(cur -> Math.max(0L, cur + delta));
    }

    // ---- Offline queue / user-addressed delivery (1.10.0-RC2) ----

    /** Messages enqueued to a user's offline queue (user was offline cluster-wide, or a send-time fallback). */
    final AtomicLong offlineEnqueued = new AtomicLong();

    /** Offline messages drained + delivered on reconnect (backfill). */
    final AtomicLong offlineDrained = new AtomicLong();

    /** Offline entries dropped by retention on the <b>TTL-drop path</b> (an entry older than {@code ttl-seconds}
     *  reaped on drain) — the bounded-gap honesty meter. NOTE: server-side {@code MAXLEN ~} stream trimming is
     *  performed by Redis on XADD and is NOT separately countable from the app, so it is not metered here. */
    final AtomicLong offlineDroppedRetention = new AtomicLong();

    /** {@code sendToUser} delivered in realtime (user online → unicast to at least one live session). */
    final AtomicLong sendToUserRealtime = new AtomicLong();

    /** {@code sendToUser} that fell through to the offline queue (user offline, or all sessions unreachable). */
    final AtomicLong sendToUserQueued = new AtomicLong();

    /** {@code sendToUser} unicast send-time failures (local MessageSessionClosedException on a bound session). */
    final AtomicLong unicastFailures = new AtomicLong();

    /** Fallback enqueue itself failed after all unicast paths — never a silent drop (logged ERROR). */
    final AtomicLong fallbackEnqueueFailures = new AtomicLong();

    /** Handshakes that resolved to a userId (authenticated/identified sessions). */
    final AtomicLong resolvedIdentities = new AtomicLong();

    /** Handshakes that resolved to null (anonymous — no offline queue / presence). */
    final AtomicLong unresolvedSessions = new AtomicLong();

    /** Local bound-user sessions on this node (the {@code users.online} gauge). Incremented on a resolved
     *  bind, decremented on unbind. Floored at 0. A per-node count of identified live sessions — not a
     *  distinct cluster-wide user count (that would need a registry scan). */
    private final AtomicLong usersOnlineLocal = new AtomicLong();

    /** Adjusts the local online-users gauge by {@code delta} (never below 0). */
    public void addUsersOnlineLocal(long delta) {
        usersOnlineLocal.updateAndGet(cur -> Math.max(0L, cur + delta));
    }

    public long getUsersOnlineLocal() { return usersOnlineLocal.get(); }

    public void incOfflineEnqueued() { offlineEnqueued.incrementAndGet(); }
    public void addOfflineDrained(long n) { offlineDrained.addAndGet(n); }
    public void addOfflineDroppedRetention(long n) { offlineDroppedRetention.addAndGet(n); }
    public void incSendToUserRealtime() { sendToUserRealtime.incrementAndGet(); }
    public void incSendToUserQueued() { sendToUserQueued.incrementAndGet(); }
    public void incUnicastFailures() { unicastFailures.incrementAndGet(); }
    public void incFallbackEnqueueFailures() { fallbackEnqueueFailures.incrementAndGet(); }
    public void incResolvedIdentities() { resolvedIdentities.incrementAndGet(); }
    public void incUnresolvedSessions() { unresolvedSessions.incrementAndGet(); }

    public long getOfflineEnqueued() { return offlineEnqueued.get(); }
    public long getOfflineDrained() { return offlineDrained.get(); }
    public long getOfflineDroppedRetention() { return offlineDroppedRetention.get(); }
    public long getSendToUserRealtime() { return sendToUserRealtime.get(); }
    public long getSendToUserQueued() { return sendToUserQueued.get(); }
    public long getUnicastFailures() { return unicastFailures.get(); }
    public long getFallbackEnqueueFailures() { return fallbackEnqueueFailures.get(); }
    public long getResolvedIdentities() { return resolvedIdentities.get(); }
    public long getUnresolvedSessions() { return unresolvedSessions.get(); }

    // ---- Multi-device presence (1.10.0-RC3) ----

    /** Aggregate presence transitions detected locally (old != new), the only case that fires an event. */
    final AtomicLong presenceChanges = new AtomicLong();
    /** PRESENCE_CHANGE events published to the reserved channel. */
    final AtomicLong presenceEventsPublished = new AtomicLong();
    /** PRESENCE_CHANGE events received from other nodes (after origin self-suppression). */
    final AtomicLong presenceEventsReceived = new AtomicLong();
    /** Own-origin PRESENCE_CHANGE echoes dropped on receive (self-suppression). */
    final AtomicLong presenceSelfDeliveryDropped = new AtomicLong();
    /** Connection-level presence writes (setPresence). */
    final AtomicLong presenceSet = new AtomicLong();
    /** OFFLINE/AWAY transitions emitted by the dead-node reap — the crash-path correction meter. */
    final AtomicLong presenceReapOffline = new AtomicLong();

    public void incPresenceChanges() { presenceChanges.incrementAndGet(); }
    public void incPresenceEventsPublished() { presenceEventsPublished.incrementAndGet(); }
    public void incPresenceEventsReceived() { presenceEventsReceived.incrementAndGet(); }
    public void incPresenceSelfDeliveryDropped() { presenceSelfDeliveryDropped.incrementAndGet(); }
    public void incPresenceSet() { presenceSet.incrementAndGet(); }
    public void incPresenceReapOffline() { presenceReapOffline.incrementAndGet(); }

    public long getPresenceChanges() { return presenceChanges.get(); }
    public long getPresenceEventsPublished() { return presenceEventsPublished.get(); }
    public long getPresenceEventsReceived() { return presenceEventsReceived.get(); }
    public long getPresenceSelfDeliveryDropped() { return presenceSelfDeliveryDropped.get(); }
    public long getPresenceSet() { return presenceSet.get(); }
    public long getPresenceReapOffline() { return presenceReapOffline.get(); }

    // ---- Mesh transport (1.10.0-RC4a) ----

    /** Frames received over the mesh TCP transport. */
    final AtomicLong meshFramesReceived = new AtomicLong();
    /** Sends that failed (no address / connect failure / async write failure). */
    final AtomicLong meshSendFailures = new AtomicLong();
    /** Frames dropped because the peer's outbound channel was not writable — the backpressure drop (RC4a M1). */
    final AtomicLong meshSendDroppedBackpressure = new AtomicLong();

    public void incMeshFramesReceived() { meshFramesReceived.incrementAndGet(); }
    public void incMeshSendFailures() { meshSendFailures.incrementAndGet(); }
    public void incMeshSendDroppedBackpressure() { meshSendDroppedBackpressure.incrementAndGet(); }

    public long getMeshFramesReceived() { return meshFramesReceived.get(); }
    public long getMeshSendFailures() { return meshSendFailures.get(); }
    public long getMeshSendDroppedBackpressure() { return meshSendDroppedBackpressure.get(); }

    // ---- Mesh observability (1.10.0-RC4d) ----

    /** Frames successfully written to a peer channel (the async writeAndFlush succeeded). */
    private final AtomicLong meshFramesSent = new AtomicLong();
    /** Outbound channels closed by the WRITER_IDLE reaper (RC4c). */
    private final AtomicLong meshIdleReaps = new AtomicLong();
    /** Send-path dials deliberately skipped while a per-peer reconnect-backoff window was open (RC4c) — NOT a failure. */
    private final AtomicLong meshReconnectBackoffSkips = new AtomicLong();
    /** Fan-out sampler (mirrors the room sampler): peers targeted per mesh broadcast. */
    private final AtomicLong meshFanoutSampleCount = new AtomicLong();
    private final AtomicLong meshFanoutTargetsTotal = new AtomicLong();
    private final AtomicLong meshFanoutTargetsLast = new AtomicLong();

    public void incMeshFramesSent() { meshFramesSent.incrementAndGet(); }
    public long getMeshFramesSent() { return meshFramesSent.get(); }
    public void incMeshIdleReaps() { meshIdleReaps.incrementAndGet(); }
    public long getMeshIdleReaps() { return meshIdleReaps.get(); }
    public void incMeshReconnectBackoffSkips() { meshReconnectBackoffSkips.incrementAndGet(); }
    public long getMeshReconnectBackoffSkips() { return meshReconnectBackoffSkips.get(); }

    /**
     * Record the peers a single mesh broadcast targeted (post interest-pruning, or all known peers when routing
     * is off / a registry read failed / a reserved channel). The fan-out reduction observation point — compare
     * {@link #getMeshFanoutTargetsAvg()} against the broker's {@code knownPeerCount()}.
     */
    public void recordMeshFanoutTargets(int targets) {
        // Increment count BEFORE total (mirrors recordRoomFanoutTargets): a concurrent getMeshFanoutTargetsAvg
        // reader (count then total) can then at worst UNDER-estimate, never report an avg above the true fan-out.
        meshFanoutSampleCount.incrementAndGet();
        meshFanoutTargetsTotal.addAndGet(targets);
        meshFanoutTargetsLast.set(targets);
    }

    public double getMeshFanoutTargetsAvg() {
        long n = meshFanoutSampleCount.get();
        return n == 0 ? 0.0 : (double) meshFanoutTargetsTotal.get() / n;
    }

    public long getMeshFanoutTargetsLast() { return meshFanoutTargetsLast.get(); }

    // ---- Public read API ----

    public long getBroadcastPublished() { return broadcastPublished.get(); }
    public long getCrossNodeBroadcastReceived() { return crossNodeBroadcastReceived.get(); }
    public long getSelfDeliveryDropped() { return selfDeliveryDropped.get(); }
    public long getUnicastSent() { return unicastSent.get(); }
    public long getPublishFailures() { return publishFailures.get(); }
    public long getBroadcastsSkippedDegraded() { return broadcastsSkippedDegraded.get(); }
    public long getReliablePublished() { return reliablePublished.get(); }
    public long getReliableReceived() { return reliableReceived.get(); }
    public long getCacheHits() { return cacheHits.get(); }
    public long getCacheMisses() { return cacheMisses.get(); }

    // ---- Room-scoped routing (1.10.0) ----
    public long getRoomBroadcastPublished() { return roomBroadcastPublished.get(); }
    public long getRoomBroadcastReceived() { return roomBroadcastReceived.get(); }
    public long getRoomFanoutStaleTarget() { return roomFanoutStaleTarget.get(); }

    /** Total target-node count summed across all room broadcasts (numerator of the average fan-out). */
    public long getRoomFanoutTargetsTotal() { return roomFanoutTargetsTotal.get(); }

    /** Number of room broadcasts whose fan-out was recorded (denominator of the average fan-out). */
    public long getRoomFanoutSampleCount() { return roomFanoutSampleCount.get(); }

    /** Target nodes on the most recent room broadcast (latest fan-out sample). */
    public long getRoomFanoutTargetsLast() { return roomFanoutTargetsLast.get(); }

    /** Average number of nodes targeted per room broadcast (the reduction meter; 0 if no samples). */
    public double getRoomFanoutTargetsAvg() {
        long n = roomFanoutSampleCount.get();
        return n == 0 ? 0.0 : (double) roomFanoutTargetsTotal.get() / n;
    }

    /** Total local room memberships on this node (the {@code members.local} gauge). */
    public long getRoomLocalMemberships() { return roomLocalMemberships.get(); }

    /**
     * Cache hit ratio (0.0 – 1.0). Returns 0 if no lookups have been performed.
     */
    public double getCacheHitRatio() {
        long hits = cacheHits.get();
        long misses = cacheMisses.get();
        long total = hits + misses;
        return total == 0 ? 0.0 : (double) hits / total;
    }

    @Override
    public String toString() {
        return "ClusterRuntimeStats{" +
                "broadcastPublished=" + broadcastPublished.get() +
                ", crossNodeReceived=" + crossNodeBroadcastReceived.get() +
                ", selfDropped=" + selfDeliveryDropped.get() +
                ", unicastSent=" + unicastSent.get() +
                ", publishFailures=" + publishFailures.get() +
                ", broadcastsSkippedDegraded=" + broadcastsSkippedDegraded.get() +
                ", cacheHits=" + cacheHits.get() +
                ", cacheMisses=" + cacheMisses.get() +
                ", cacheHitRatio=" + String.format("%.2f", getCacheHitRatio()) +
                '}';
    }
}
