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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterReaper;
import io.nats.client.KeyValue;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;

/**
 * NATS JetStream-KV implementation of {@link ClusterReaper} (bucket {@code netty-reaping}, with a bounded maxAge =
 * claim window). {@code tryClaim} uses KV {@code create} (atomic create-if-absent — the {@code SET NX} analog):
 * only the first caller within the window succeeds. On any other error it reaps anyway (cleanup is idempotent),
 * matching {@code RedisClusterReaper}.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class NatsKvReaper implements ClusterReaper {

    private final KeyValue kv;

    public NatsKvReaper(KeyValue kv) {
        this.kv = kv;
    }

    @Override
    public boolean tryClaim(String deadNodeId, String reaperNodeId, long claimWindowMs) {
        try {
            kv.create("r." + deadNodeId, reaperNodeId.getBytes(StandardCharsets.UTF_8));
            log.debug("Node {} claimed reaping of dead node {} (NATS KV)", reaperNodeId, deadNodeId);
            return true;
        } catch (io.nats.client.JetStreamApiException existsOrApi) {
            // create() on an existing key → the JetStream "wrong last sequence" API error
            // (apiErrorCode 10071): another node already claimed within the window.
            return false;
        } catch (Exception e) {
            // On any other error prefer correctness over dedup: reap anyway (cleanup is idempotent).
            log.debug("Reap-claim for dead node {} errored; proceeding with cleanup", deadNodeId, e);
            return true;
        }
    }
}
