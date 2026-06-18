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
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis {@link MeshInterestRegistry} — the per-URI interest node-set (1.10.0-RC4b). Mirrors
 * {@code RedisRoomRegistry} with the room dimension collapsed away (and no local index — interest serves only the
 * send-side routing read, not a receive-hot-path local query).
 *
 * <p>Keys (base64url URI + Redis-Cluster hash tag {@code {b64uri}} so both keys for a URI co-locate on one slot,
 * making the JOIN/LEAVE {@code EVAL} single-slot):
 * <ul>
 *   <li>{@code netty:interest:{b64uri}:nodes} &rarr; Set&lt;nodeId&gt;: nodes hosting &ge;1 live session. <b>The routing key.</b></li>
 *   <li>{@code netty:interest:{b64uri}:n:{nodeId}} &rarr; Set&lt;sessionId&gt;: this node's live sessions for the URI
 *       (the per-node refcount that decides the node-set 0&harr;1 inside the Lua).</li>
 * </ul>
 *
 * @author berrywang1996
 * @since V1.10.0
 */
@Slf4j
public class RedisMeshInterestRegistry implements MeshInterestRegistry {

    private static final String PREFIX = "netty:interest:";

    /** SUBSCRIBE: KEYS[1]=per-node session set, KEYS[2]=node-set. ARGV[1]=sessionId, ARGV[2]=nodeId.
     *  SADD session; if the per-node set was empty before &rarr; SADD node to the node-set. Returns 1 if newly added. */
    private static final String SUBSCRIBE_LUA =
            "local before = redis.call('SCARD', KEYS[1]) "
          + "redis.call('SADD', KEYS[1], ARGV[1]) "
          + "local added = 0 "
          + "if before == 0 then "
          + "  redis.call('SADD', KEYS[2], ARGV[2]) "
          + "  added = 1 "
          + "end "
          + "return added";

    /** UNSUBSCRIBE: KEYS[1]=per-node session set, KEYS[2]=node-set. ARGV[1]=sessionId, ARGV[2]=nodeId.
     *  SREM session; if the per-node set is now empty &rarr; SREM node from the node-set + DEL the set. Returns 1 if removed. */
    private static final String UNSUBSCRIBE_LUA =
            "redis.call('SREM', KEYS[1], ARGV[1]) "
          + "local removed = 0 "
          + "if redis.call('SCARD', KEYS[1]) == 0 then "
          + "  redis.call('SREM', KEYS[2], ARGV[2]) "
          + "  redis.call('DEL', KEYS[1]) "
          + "  removed = 1 "
          + "end "
          + "return removed";

    private final StatefulRedisConnection<String, String> connection;
    private final ExecutorService executor;

    public RedisMeshInterestRegistry(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
        final AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cluster-mesh-interest-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletionStage<Void> subscribe(String uri, String sessionId, String nodeId) {
        String tag = "{" + b64(uri) + "}";
        String memberKey = PREFIX + tag + ":n:" + nodeId;
        String nodesKey = PREFIX + tag + ":nodes";
        return connection.async().<Long>eval(SUBSCRIBE_LUA, ScriptOutputType.INTEGER,
                        new String[]{ memberKey, nodesKey }, sessionId, nodeId)
                .thenAccept(added -> log.debug("interest subscribe {} on URI {} (node {}, node-set-added={})",
                        sessionId, uri, nodeId, added))
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Void> unsubscribe(String uri, String sessionId, String nodeId) {
        String tag = "{" + b64(uri) + "}";
        String memberKey = PREFIX + tag + ":n:" + nodeId;
        String nodesKey = PREFIX + tag + ":nodes";
        return connection.async().<Long>eval(UNSUBSCRIBE_LUA, ScriptOutputType.INTEGER,
                        new String[]{ memberKey, nodesKey }, sessionId, nodeId)
                .thenAccept(removed -> log.debug("interest unsubscribe {} on URI {} (node {}, node-set-removed={})",
                        sessionId, uri, nodeId, removed))
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Set<String>> nodesForUri(String uri) {
        String nodesKey = PREFIX + "{" + b64(uri) + "}:nodes";
        return connection.async().smembers(nodesKey)
                .thenApply(members -> members == null ? Collections.<String>emptySet() : members)
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Void> removeAllForNode(String nodeId) {
        return CompletableFuture.runAsync(() -> {
            RedisCommands<String, String> sync = connection.sync();
            String suffix = ":n:" + nodeId;
            String pattern = PREFIX + "*" + suffix;
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs args = ScanArgs.Builder.matches(pattern).limit(100);
            List<String> memberKeys = new ArrayList<>();
            do {
                var scan = sync.scan(cursor, args);
                for (String key : scan.getKeys()) {
                    if (key.startsWith(PREFIX) && key.endsWith(suffix)) {
                        memberKeys.add(key);
                    }
                }
                cursor = scan;
            } while (!cursor.isFinished());

            RedisAsyncCommands<String, String> async = connection.async();
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (String memberKey : memberKeys) {
                String nodesKey = memberKey.substring(0, memberKey.length() - suffix.length()) + ":nodes";
                futures.add(async.srem(nodesKey, nodeId).toCompletableFuture());
                futures.add(async.del(memberKey).toCompletableFuture());
            }
            if (!futures.isEmpty()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
            log.info("Removed dead node {} from {} interest member set(s)", nodeId, memberKeys.size());
        }, executor);
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
        log.info("RedisMeshInterestRegistry shut down");
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
