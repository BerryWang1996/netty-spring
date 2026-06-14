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
