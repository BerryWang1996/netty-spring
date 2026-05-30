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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.node;

/**
 * Leader-election for reconciliation cleanup. When a node dies, every surviving node's
 * reconciliation independently detects it; to avoid N-fold cleanup, each node first asks the
 * reaper to claim the dead node, and only the claim winner performs the cleanup.
 *
 * <p>The default ({@link #alwaysReap()}) grants every claim — correct (cleanup is idempotent) but
 * not deduplicated, matching pre-1.9.0 behavior. The Redis implementation
 * ({@code RedisClusterReaper}) uses {@code SET NX PX} for a true single winner per window.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@FunctionalInterface
public interface ClusterReaper {

    /**
     * Attempts to claim the exclusive right to reap a dead node.
     *
     * @param deadNodeId    the dead node to clean up
     * @param reaperNodeId  this node's id (the claim owner)
     * @param claimWindowMs how long the claim is held (ms) before it can be re-claimed
     * @return true if this node won the claim and should perform cleanup
     */
    boolean tryClaim(String deadNodeId, String reaperNodeId, long claimWindowMs);

    /** A reaper that grants every claim (no dedup). Default when no distributed reaper is wired. */
    static ClusterReaper alwaysReap() {
        return (deadNodeId, reaperNodeId, claimWindowMs) -> true;
    }
}
