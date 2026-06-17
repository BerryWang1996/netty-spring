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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshNodeDirectory;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.SetArgs;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis-backed {@link MeshNodeDirectory} (1.10.0-RC4a): advertises {@code nodeId → host:port} as
 * {@code netty:mesh:addr:{b64nodeId}} with a TTL, and discovers peers by SCAN. Mirrors {@code RedisUserRegistry}'s
 * dedicated-executor + async idiom; reuses the cluster Redis connection (discovery is OFF the message hot path).
 *
 * @author berrywang1996
 * @since V1.10.0
 */
@Slf4j
public class RedisMeshNodeDirectory implements MeshNodeDirectory {

    private static final String PREFIX = "netty:mesh:addr:";

    private final StatefulRedisConnection<String, String> connection;
    private final ExecutorService executor;

    public RedisMeshNodeDirectory(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
        final AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cluster-mesh-dir-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletionStage<Void> advertise(String nodeId, String host, int port, long ttlMs) {
        return connection.async()
                .set(key(nodeId), host + ":" + port, SetArgs.Builder.px(Math.max(1, ttlMs)))
                .thenAccept(ok -> log.debug("Advertised mesh node {} at {}:{} (ttl {}ms)", nodeId, host, port, ttlMs))
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Map<String, String>> peers(String selfNodeId) {
        return CompletableFuture.supplyAsync(() -> {
            RedisCommands<String, String> sync = connection.sync();
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs args = ScanArgs.Builder.matches(PREFIX + "*").limit(100);
            List<String> keys = new ArrayList<>();
            do {
                var scan = sync.scan(cursor, args);
                for (String k : scan.getKeys()) {
                    if (k.startsWith(PREFIX)) {
                        keys.add(k);
                    }
                }
                cursor = scan;
            } while (!cursor.isFinished());

            Map<String, String> out = new HashMap<>();
            for (String k : keys) {
                String nodeId = nodeIdOf(k);
                if (nodeId == null || nodeId.equals(selfNodeId)) {
                    continue;
                }
                String addr = sync.get(k);
                if (addr != null && !addr.isEmpty()) {
                    out.put(nodeId, addr);
                }
            }
            return out;
        }, executor);
    }

    @Override
    public CompletionStage<Void> remove(String nodeId) {
        return connection.async().del(key(nodeId)).thenAccept(n -> { }).toCompletableFuture();
    }

    @Override
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            executor.shutdownNow();
        }
        log.info("RedisMeshNodeDirectory shut down");
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String key(String nodeId) {
        return PREFIX + b64(nodeId);
    }

    private static String nodeIdOf(String key) {
        if (!key.startsWith(PREFIX)) {
            return null;
        }
        try {
            return new String(Base64.getUrlDecoder().decode(key.substring(PREFIX.length())), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
