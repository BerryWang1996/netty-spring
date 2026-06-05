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
import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import io.lettuce.core.cluster.api.sync.RedisAdvancedClusterCommands;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Redis-Cluster (topology) implementation of {@link SessionRegistry}. Same key design as
 * {@code RedisSessionRegistry} (the URI token is base64url-encoded so a URI that is a prefix of
 * another cannot leak/extract the wrong session ids), but deregister is NON-ATOMIC (HGET -> DEL + SREM as separately
 * slot-routed commands — an EVAL touching both the session key and the node-set key would be
 * CROSSSLOT; the race it would close is theoretical under UUID sessionIds), and
 * {@code clusterSessionIds} uses the cluster-aware SCAN (all masters).
 *
 * @author berrywang1996
 * @since V1.9.0
 */
@Slf4j
public class RedisClusterModeSessionRegistry implements SessionRegistry {

    private static final String SESSION_PREFIX = "netty:session:";
    private static final String NODE_PREFIX = "netty:node:";
    private static final String NODE_ID_FIELD = "nodeId";

    private final StatefulRedisClusterConnection<String, String> connection;

    public RedisClusterModeSessionRegistry(StatefulRedisClusterConnection<String, String> connection) {
        this.connection = connection;
    }

    @Override
    public CompletionStage<Void> register(String uri, String sessionId, String nodeId, Map<String, String> metadata) {
        RedisAdvancedClusterAsyncCommands<String, String> async = connection.async();
        Map<String, String> hash = new HashMap<>(metadata);
        hash.put(NODE_ID_FIELD, nodeId);
        CompletableFuture<String> hset = async.hmset(sessionKey(uri, sessionId), hash).toCompletableFuture();
        CompletableFuture<Long> sadd = async.sadd(nodeSetKey(nodeId), memberOf(uri, sessionId)).toCompletableFuture();
        return CompletableFuture.allOf(hset, sadd).thenRun(() ->
                log.debug("Registered session {} on node {} for URI {}", sessionId, nodeId, uri));
    }

    @Override
    public CompletionStage<Void> deregister(String uri, String sessionId) {
        RedisAdvancedClusterAsyncCommands<String, String> async = connection.async();
        String sessionKey = sessionKey(uri, sessionId);
        String member = memberOf(uri, sessionId);
        return async.hget(sessionKey, NODE_ID_FIELD).thenCompose(nodeId -> {
            if (nodeId == null) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Long> del = async.del(sessionKey).toCompletableFuture();
            CompletableFuture<Long> srem = async.srem(nodeSetKey(nodeId), member).toCompletableFuture();
            return CompletableFuture.allOf(del, srem);
        }).thenRun(() -> log.debug("Deregistered session {} for URI {}", sessionId, uri)).toCompletableFuture();
    }

    @Override
    public CompletionStage<String> lookupNode(String uri, String sessionId) {
        return connection.async().hget(sessionKey(uri, sessionId), NODE_ID_FIELD).toCompletableFuture();
    }

    @Override
    public CompletionStage<Set<String>> clusterSessionIds(String uri) {
        return CompletableFuture.supplyAsync(() -> {
            RedisAdvancedClusterCommands<String, String> sync = connection.sync();
            String prefix = SESSION_PREFIX + b64(uri) + ":";
            Set<String> ids = new HashSet<>();
            ScanArgs args = ScanArgs.Builder.matches(prefix + "*").limit(100);
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                var res = sync.scan(cursor, args);
                for (String key : res.getKeys()) {
                    if (key.startsWith(prefix)) {
                        ids.add(key.substring(prefix.length()));
                    }
                }
                cursor = res;
            } while (!cursor.isFinished());
            return ids;
        });
    }

    @Override
    public CompletionStage<Void> removeAllForNode(String nodeId) {
        RedisAdvancedClusterAsyncCommands<String, String> async = connection.async();
        String nodeSetKey = nodeSetKey(nodeId);
        return async.smembers(nodeSetKey).thenCompose(members -> {
            if (members == null || members.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (String member : members) {
                // member format: "b64url(uri)|sessionId" — token already base64url-encoded.
                int sep = member.indexOf('|');
                if (sep > 0) {
                    String b64uri = member.substring(0, sep);
                    String sessionId = member.substring(sep + 1);
                    futures.add(async.del(SESSION_PREFIX + b64uri + ":" + sessionId).toCompletableFuture());
                }
            }
            futures.add(async.del(nodeSetKey).toCompletableFuture());
            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]));
        }).thenRun(() -> log.info("Removed all sessions for dead node {}", nodeId)).toCompletableFuture();
    }

    @Override
    public void shutdown() {
        log.info("RedisClusterModeSessionRegistry shut down");
    }

    /** Base64url (no padding) of the URI — delimiter-safe (no ':' or '|'). */
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
