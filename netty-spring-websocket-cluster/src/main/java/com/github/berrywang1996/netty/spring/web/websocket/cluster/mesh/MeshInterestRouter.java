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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.mesh;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshInterestRegistry;
import lombok.extern.slf4j.Slf4j;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Send-side interest routing for {@link MeshBroker} (1.10.0-RC4b): a short-TTL cache over
 * {@link MeshInterestRegistry#nodesForUri} with the RC4b safety contract.
 *
 * <p><b>Sentinel (deliberate divergence from RC1 {@code nodesForRoomCached}):</b> {@link #nodesForUriCached} returns
 * <b>null</b> for a reserved channel, an absent registry, or a read failure/timeout (&rArr; caller falls back to
 * all-peers; the failure is NOT cached), and a <b>non-null (possibly empty)</b> set only on a successful authoritative
 * read (empty &rArr; prune all, the publisher already did local fan-out). The cache lives here (owned by the broker),
 * so the dead-node clear is reached via {@link #onNodeLeft}.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
@Slf4j
public class MeshInterestRouter {

    private final MeshInterestRegistry registry;
    private final Set<String> reservedChannels;
    private final long cacheTtlMs;
    private final long lookupTimeoutMs;
    private final ConcurrentHashMap<String, CachedNodeSet> cache = new ConcurrentHashMap<>();

    public MeshInterestRouter(MeshInterestRegistry registry, Set<String> reservedChannels,
                              long cacheTtlMs, long lookupTimeoutMs) {
        this.registry = registry;
        this.reservedChannels = reservedChannels == null ? Collections.emptySet() : reservedChannels;
        this.cacheTtlMs = cacheTtlMs;
        this.lookupTimeoutMs = lookupTimeoutMs;
    }

    /** The interested node-set for {@code uri}, or {@code null} &rArr; all-peers fallback (reserved / no registry /
     *  read failure). A non-null empty set is authoritative (no remote audience). */
    public Set<String> nodesForUriCached(String uri) {
        if (registry == null || reservedChannels.contains(uri)) {
            return null; // reserved/control channel or no registry ⇒ never prune ⇒ all-peers
        }
        CachedNodeSet cached = cache.get(uri);
        if (cached != null && !cached.isExpired(cacheTtlMs)) {
            return cached.nodes;
        }
        try {
            Set<String> nodes = registry.nodesForUri(uri).toCompletableFuture()
                    .get(lookupTimeoutMs, TimeUnit.MILLISECONDS);
            Set<String> snapshot = nodes == null ? Collections.emptySet() : new HashSet<>(nodes);
            cache.put(uri, new CachedNodeSet(snapshot, System.currentTimeMillis()));
            return snapshot;
        } catch (Exception e) {
            // Read failure/timeout ⇒ null ⇒ all-peers fallback. NOT cached (a transient blip must not pin all-peers
            // for a full TTL window), mirroring RC1 nodesForRoomCached's cache-only-on-success.
            log.warn("interest nodesForUri({}) lookup failed — falling back to all-peers this round", uri, e);
            return null;
        }
    }

    /** Dead-node reap: clear the send cache wholesale (keyed by URI, not node) + scrub the node from the registry. */
    public void onNodeLeft(String nodeId) {
        cache.clear();
        try {
            registry.removeAllForNode(nodeId);
        } catch (Exception e) {
            log.debug("interest registry cleanup for dead node {} failed", nodeId, e);
        }
    }

    private static final class CachedNodeSet {
        final Set<String> nodes;
        final long cachedAtMs;

        CachedNodeSet(Set<String> nodes, long cachedAtMs) {
            this.nodes = nodes;
            this.cachedAtMs = cachedAtMs;
        }

        boolean isExpired(long ttlMs) {
            return System.currentTimeMillis() - cachedAtMs > ttlMs;
        }
    }
}
