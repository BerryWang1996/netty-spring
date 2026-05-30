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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import lombok.extern.slf4j.Slf4j;

/**
 * Redis-backed {@link ClusterReaper}: claims the right to reap a dead node with
 * {@code SET netty:cluster:reaping:{deadNodeId} {reaperNodeId} NX PX {windowMs}}. Only the first
 * caller within the window gets {@code OK}; the rest are locked out, so a dead node is cleaned up once.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class RedisClusterReaper implements ClusterReaper {

    private static final String REAP_PREFIX = "netty:cluster:reaping:";

    private final StatefulRedisConnection<String, String> connection;

    public RedisClusterReaper(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public boolean tryClaim(String deadNodeId, String reaperNodeId, long claimWindowMs) {
        try {
            String res = connection.sync().set(REAP_PREFIX + deadNodeId, reaperNodeId,
                    SetArgs.Builder.nx().px(Math.max(1, claimWindowMs)));
            boolean won = "OK".equals(res);
            if (won) {
                log.debug("Node {} claimed reaping of dead node {}", reaperNodeId, deadNodeId);
            }
            return won;
        } catch (Exception e) {
            // On a Redis error prefer correctness over dedup: reap anyway (cleanup is idempotent).
            log.debug("Reap-claim for dead node {} failed; proceeding with cleanup", deadNodeId, e);
            return true;
        }
    }
}
