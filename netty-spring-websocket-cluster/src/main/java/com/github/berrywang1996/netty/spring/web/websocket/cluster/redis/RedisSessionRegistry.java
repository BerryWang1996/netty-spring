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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRegistry;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Redis implementation of {@link SessionRegistry}.
 *
 * <p>Redis key design (the URI token is base64url-encoded so a URI containing ':' that is a
 * prefix of another URI cannot leak/extract the wrong session ids — base64url is delimiter-safe,
 * containing neither ':' nor '|'):
 * <ul>
 *   <li>{@code netty:session:{b64url(uri)}:{sessionId}} → Hash { nodeId, ... metadata }</li>
 *   <li>{@code netty:node:{nodeId}:sessions} → Set { "b64url(uri)|sessionId", ... }</li>
 * </ul>
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
public class RedisSessionRegistry implements SessionRegistry {

    private static final String SESSION_PREFIX = "netty:session:";
    private static final String NODE_PREFIX = "netty:node:";
    private static final String NODE_ID_FIELD = "nodeId";

    private final StatefulRedisConnection<String, String> connection;

    /**
     * Atomic deregister: HGET the owning nodeId, then DEL the session hash and SREM the node-set
     * member — in one script so a concurrent re-register of the same uri|sessionId cannot interleave.
     * KEYS[1] = sessionKey; ARGV[1] = "uri|sessionId" member. The node-set key is derived from the
     * stored nodeId (safe on standalone/sentinel; not Redis-Cluster cross-slot safe — that client is
     * a roadmap item). Returns the removed nodeId (or nil).
     */
    private static final String DEREGISTER_LUA =
            "local nodeId = redis.call('HGET', KEYS[1], 'nodeId') "
          + "if nodeId then "
          + "  redis.call('DEL', KEYS[1]) "
          + "  redis.call('SREM', 'netty:node:' .. nodeId .. ':sessions', ARGV[1]) "
          + "end "
          + "return nodeId";

    public RedisSessionRegistry(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public CompletionStage<Void> register(String uri, String sessionId, String nodeId,
                                          Map<String, String> metadata) {
        RedisAsyncCommands<String, String> async = connection.async();
        String sessionKey = sessionKey(uri, sessionId);
        String nodeSetKey = nodeSetKey(nodeId);

        Map<String, String> hash = new HashMap<>(metadata);
        hash.put(NODE_ID_FIELD, nodeId);

        // Pipeline: HSET session key + SADD to node's session set
        CompletableFuture<String> hset = async.hmset(sessionKey, hash).toCompletableFuture();
        CompletableFuture<Long> sadd = async.sadd(nodeSetKey, memberOf(uri, sessionId)).toCompletableFuture();

        return CompletableFuture.allOf(hset, sadd).thenRun(() ->
                log.debug("Registered session {} on node {} for URI {}", sessionId, nodeId, uri));
    }

    @Override
    public CompletionStage<Void> deregister(String uri, String sessionId) {
        String sessionKey = sessionKey(uri, sessionId);
        String member = memberOf(uri, sessionId);
        // Single atomic Lua call replaces the former non-atomic HGET → DEL + SREM. Plain EVAL: Redis
        // caches the compiled script by SHA, and the body is tiny, so resending it is negligible.
        return connection.async().<String>eval(DEREGISTER_LUA, ScriptOutputType.VALUE,
                        new String[]{ sessionKey }, member)
                .thenAccept(removedNodeId ->
                        log.debug("Deregistered session {} for URI {} (was on node {})",
                                sessionId, uri, removedNodeId))
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<String> lookupNode(String uri, String sessionId) {
        String sessionKey = sessionKey(uri, sessionId);
        return connection.async().hget(sessionKey, NODE_ID_FIELD)
                .thenApply(nodeId -> nodeId)
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Set<String>> clusterSessionIds(String uri) {
        // Use SCAN instead of KEYS to avoid blocking the Redis event loop on large keyspaces.
        // Still O(N) total but doesn't hold the Redis single-thread for the entire duration.
        return CompletableFuture.supplyAsync(() -> {
            RedisCommands<String, String> sync = connection.sync();
            String prefix = SESSION_PREFIX + b64(uri) + ":";
            String pattern = prefix + "*";
            Set<String> sessionIds = new HashSet<>();
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs args = ScanArgs.Builder.matches(pattern).limit(100);
            do {
                var scanResult = sync.scan(cursor, args);
                for (String key : scanResult.getKeys()) {
                    if (key.startsWith(prefix)) {
                        sessionIds.add(key.substring(prefix.length()));
                    }
                }
                cursor = scanResult;
            } while (!cursor.isFinished());
            return sessionIds;
        });
    }

    @Override
    public CompletionStage<Void> removeAllForNode(String nodeId) {
        RedisAsyncCommands<String, String> async = connection.async();
        String nodeSetKey = nodeSetKey(nodeId);

        return async.smembers(nodeSetKey).thenCompose(members -> {
            if (members == null || members.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }

            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (String member : members) {
                // member format: "b64url(uri)|sessionId" — the token is already base64url-encoded,
                // so the session key is the prefix + token + ':' + sessionId (no decode needed).
                int sep = member.indexOf('|');
                if (sep > 0) {
                    String b64uri = member.substring(0, sep);
                    String sessionId = member.substring(sep + 1);
                    futures.add(async.del(SESSION_PREFIX + b64uri + ":" + sessionId).toCompletableFuture());
                }
            }
            futures.add(async.del(nodeSetKey).toCompletableFuture());

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        }).thenRun(() ->
                log.info("Removed all sessions for dead node {}", nodeId))
                .toCompletableFuture();
    }

    @Override
    public void shutdown() {
        // Don't close the connection — it's owned by the caller (auto-config).
        log.info("RedisSessionRegistry shut down");
    }

    // ---- Key helpers ----

    /** Base64url (no padding) of the URI — delimiter-safe (no ':' or '|'), so a URI that is a
     *  prefix of another can never produce an ambiguous key/member. */
    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String sessionKey(String uri, String sessionId) {
        return SESSION_PREFIX + b64(uri) + ":" + sessionId;
    }

    /** Node-set member: {@code b64url(uri)|sessionId}. */
    private static String memberOf(String uri, String sessionId) {
        return b64(uri) + "|" + sessionId;
    }

    private static String nodeSetKey(String nodeId) {
        return NODE_PREFIX + nodeId + ":sessions";
    }
}
