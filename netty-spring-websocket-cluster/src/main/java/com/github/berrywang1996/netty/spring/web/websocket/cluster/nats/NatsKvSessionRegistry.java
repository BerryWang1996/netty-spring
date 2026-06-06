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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * NATS JetStream-KV implementation of {@link SessionRegistry} (bucket {@code netty-sessions}). Used in the
 * all-NATS deployment (no Redis). Keys are NATS-KV-legal: {@code s.<b64url(uri)>.<sessionId>} for the session
 * → nodeId entry, plus {@code n.<b64url(nodeId)>.<b64url(uri)>.<sessionId>} membership keys for
 * {@link #removeAllForNode}. Deregister is NON-ATOMIC (read owner, then delete the two keys) — same theoretical
 * race as the Redis-Cluster impl under UUID sessionIds. KV ops are blocking, so methods wrap them in async stages
 * (mirroring {@code RedisSessionRegistry.clusterSessionIds}).
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class NatsKvSessionRegistry implements SessionRegistry {

    private final KeyValue kv;

    /**
     * Dedicated bounded pool for the blocking jnats KV calls. Without this, every {@code runAsync}/
     * {@code supplyAsync} below would run on {@link java.util.concurrent.ForkJoinPool#commonPool()}
     * (sized to {@code cores - 1}), where blocking NATS I/O on the unicast hot path starves the shared
     * pool used by the rest of the JVM. A small fixed pool keeps the blocking work isolated.
     */
    private final ExecutorService executor;

    public NatsKvSessionRegistry(KeyValue kv) {
        this.kv = kv;
        this.executor = Executors.newFixedThreadPool(4, new ThreadFactory() {
            private final AtomicInteger seq = new AtomicInteger();
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "nats-kv-registry-" + seq.incrementAndGet());
                t.setDaemon(true);
                return t;
            }
        });
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
    private static String sessionKey(String uri, String sessionId) { return "s." + b64(uri) + "." + sessionId; }
    private static String memberKey(String nodeId, String uri, String sessionId) {
        return "n." + b64(nodeId) + "." + b64(uri) + "." + sessionId;
    }

    @Override
    public CompletionStage<Void> register(String uri, String sessionId, String nodeId, Map<String, String> metadata) {
        return CompletableFuture.runAsync(() -> {
            try {
                // Put the membership key FIRST, then the session key. If the second put fails, the
                // membership key is "over-set" — but removeAllForNode iterates membership keys and
                // reaps both the membership entry and the (possibly missing) session entry, so an
                // over-set is always recoverable. The reverse order would orphan the session key
                // (no membership entry to drive cleanup), leaving a stale session→node mapping.
                kv.put(memberKey(nodeId, uri, sessionId), new byte[0]);
                kv.put(sessionKey(uri, sessionId), nodeId.getBytes(StandardCharsets.UTF_8));
            } catch (Exception e) {
                throw new java.util.concurrent.CompletionException("NATS KV register failed", e);
            }
        }, executor);
    }

    @Override
    public CompletionStage<Void> deregister(String uri, String sessionId) {
        return CompletableFuture.runAsync(() -> {
            try {
                KeyValueEntry e = kv.get(sessionKey(uri, sessionId));
                if (e == null) {
                    return;
                }
                String nodeId = e.getValueAsString();
                kv.delete(sessionKey(uri, sessionId));
                if (nodeId != null) {
                    kv.delete(memberKey(nodeId, uri, sessionId));
                }
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException("NATS KV deregister failed", ex);
            }
        }, executor);
    }

    @Override
    public CompletionStage<String> lookupNode(String uri, String sessionId) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                KeyValueEntry e = kv.get(sessionKey(uri, sessionId));
                return e == null ? null : e.getValueAsString();
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException("NATS KV lookup failed", ex);
            }
        }, executor);
    }

    @Override
    public CompletionStage<Set<String>> clusterSessionIds(String uri) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                String prefix = "s." + b64(uri) + ".";
                Set<String> ids = new HashSet<>();
                for (String key : kv.keys()) {
                    if (key.startsWith(prefix)) {
                        ids.add(key.substring(prefix.length()));
                    }
                }
                return ids;
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException("NATS KV clusterSessionIds failed", ex);
            }
        }, executor);
    }

    @Override
    public CompletionStage<Void> removeAllForNode(String nodeId) {
        return CompletableFuture.runAsync(() -> {
            try {
                String prefix = "n." + b64(nodeId) + ".";
                for (String key : kv.keys()) {
                    if (key.startsWith(prefix)) {
                        // key = n.<b64(nodeId)>.<b64(uri)>.<sessionId>
                        String rest = key.substring(prefix.length());
                        int dot = rest.lastIndexOf('.');
                        // dot >= 0 (not > 0): for empty-uri membership keys (n.<b64nodeId>..<sid>),
                        // rest is ".<sid>" and lastIndexOf('.') == 0. substring(0, 0) yields "", so
                        // the reconstructed session key becomes "s..<sid>" — which is exactly what
                        // register() wrote for an empty URI. dot > 0 would silently leak it.
                        if (dot >= 0) {
                            String b64uri = rest.substring(0, dot);
                            String sessionId = rest.substring(dot + 1);
                            kv.delete("s." + b64uri + "." + sessionId);
                        }
                        kv.delete(key);
                    }
                }
            } catch (Exception ex) {
                throw new java.util.concurrent.CompletionException("NATS KV removeAllForNode failed", ex);
            }
        }, executor);
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        log.info("NatsKvSessionRegistry shut down");
    }
}
