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

    /** Node lookup cache hits. */
    final AtomicLong cacheHits = new AtomicLong();

    /** Node lookup cache misses (fell through to registry). */
    final AtomicLong cacheMisses = new AtomicLong();

    // ---- Public read API ----

    public long getBroadcastPublished() { return broadcastPublished.get(); }
    public long getCrossNodeBroadcastReceived() { return crossNodeBroadcastReceived.get(); }
    public long getSelfDeliveryDropped() { return selfDeliveryDropped.get(); }
    public long getUnicastSent() { return unicastSent.get(); }
    public long getPublishFailures() { return publishFailures.get(); }
    public long getCacheHits() { return cacheHits.get(); }
    public long getCacheMisses() { return cacheMisses.get(); }

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
                ", cacheHits=" + cacheHits.get() +
                ", cacheMisses=" + cacheMisses.get() +
                ", cacheHitRatio=" + String.format("%.2f", getCacheHitRatio()) +
                '}';
    }
}
