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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.room;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.SessionRef;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserRegistry;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis implementation of {@link UserRegistry} (1.10.0-RC2) — the {@code userId → live sessions} reverse
 * index for user-addressed delivery.
 *
 * <p><b>Redis key design</b> (userId base64url-encoded so a ':'-containing or prefixing userId can't leak the
 * wrong set; base64url is delimiter-safe — no ':' / '|' / '{' / '}'). The hash tag {@code {b64userId}} pins a
 * user's set to a single Redis-Cluster slot:
 * <ul>
 *   <li>{@code netty:user:{b64userId}} → Set of members {@code nodeId|b64uri|sessionId}. The nodeId is the
 *       leading token so {@link #removeAllForNode} can prefix-match a dead node's members cheaply.</li>
 * </ul>
 *
 * <p><b>No-cache lookups</b>: {@link #sessionsForUser} / {@link #isUserOnline} hit Redis (SMEMBERS / SCARD)
 * on <b>every</b> call — they are NOT cached. Caching presence would create a false-online silent-loss
 * window (a just-disconnected user reads "online" → fire-and-forget unicast to a dead session → no fallback →
 * silent loss). See the RC2 spec §5/§6 + the design-review decision record. These calls are on the
 * relatively cold {@code sendToUser} path, so the fresh read is affordable.
 *
 * <p><b>Atomicity</b>: {@code bindUser}/{@code unbindUser} are single SADD/SREM — atomic by nature; no Lua
 * needed (unlike the room registry's node-set transition).
 *
 * <p><b>Threading</b>: the blocking {@code removeAllForNode} SCAN runs on a small dedicated executor (mirrors
 * {@code RedisRoomRegistry}) so the user-index control path never borrows a transport I/O thread.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
@Slf4j
public class RedisUserRegistry implements UserRegistry {

    private static final String USER_PREFIX = "netty:user:";

    private final StatefulRedisConnection<String, String> connection;
    private final ExecutorService executor;

    public RedisUserRegistry(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
        final AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cluster-user-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletionStage<Void> bindUser(String userId, String uri, String sessionId, String nodeId) {
        String key = userKey(userId);
        String member = memberOf(nodeId, uri, sessionId);
        return connection.async().sadd(key, member)
                .thenAccept(added -> log.debug("Bound user {} session {} on node {} for URI {}",
                        userId, sessionId, nodeId, uri))
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Void> unbindUser(String userId, String uri, String sessionId) {
        String key = userKey(userId);
        // The nodeId is part of the member; but unbind is called on the OWNING node (local disconnect), so we
        // know nodeId — however the SPI signature omits it to mirror SessionRegistry.deregister. We SREM by
        // matching the (b64uri, sessionId) suffix across this user's members (a user has few sessions, so the
        // SMEMBERS+filter is cheap and avoids needing the nodeId here).
        String suffix = "|" + b64(uri) + "|" + sessionId;
        RedisAsyncCommands<String, String> async = connection.async();
        return async.smembers(key).thenCompose(members -> {
            if (members == null || members.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            List<String> toRemove = new ArrayList<>();
            for (String m : members) {
                if (m.endsWith(suffix)) {
                    toRemove.add(m);
                }
            }
            if (toRemove.isEmpty()) {
                return CompletableFuture.completedFuture(null);
            }
            return async.srem(key, toRemove.toArray(new String[0]))
                    .thenAccept(removed -> log.debug("Unbound user {} session {} for URI {} (removed {})",
                            userId, sessionId, uri, removed))
                    .toCompletableFuture();
        }).toCompletableFuture();
    }

    @Override
    public CompletionStage<Set<SessionRef>> sessionsForUser(String userId) {
        // NOT cached — fresh SMEMBERS every call (avoids the false-online silent-loss window).
        return connection.async().smembers(userKey(userId))
                .thenApply(members -> {
                    Set<SessionRef> refs = new HashSet<>();
                    if (members != null) {
                        for (String m : members) {
                            SessionRef ref = parseMember(m);
                            if (ref != null) {
                                refs.add(ref);
                            }
                        }
                    }
                    return refs;
                })
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Boolean> isUserOnline(String userId) {
        // NOT cached — fresh SCARD every call.
        return connection.async().scard(userKey(userId))
                .thenApply(card -> card != null && card > 0)
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Void> removeAllForNode(String nodeId) {
        // SCAN every user set; for each, SREM the dead node's members (those whose member starts with
        // "nodeId|"). Pipelined; SCAN keeps the Redis event loop free. (Cluster note: SCAN over a
        // RedisClusterConnection iterates all masters; SREM is single-key so it is slot-safe.)
        return CompletableFuture.runAsync(() -> {
            RedisCommands<String, String> sync = connection.sync();
            String pattern = USER_PREFIX + "*";
            String memberPrefix = nodeId + "|";
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs args = ScanArgs.Builder.matches(pattern).limit(100);
            List<String> userKeys = new ArrayList<>();
            do {
                var scan = sync.scan(cursor, args);
                for (String key : scan.getKeys()) {
                    if (key.startsWith(USER_PREFIX)) {
                        userKeys.add(key);
                    }
                }
                cursor = scan;
            } while (!cursor.isFinished());

            RedisAsyncCommands<String, String> async = connection.async();
            List<CompletableFuture<?>> futures = new ArrayList<>();
            int prunedUsers = 0;
            for (String userKey : userKeys) {
                Set<String> members = sync.smembers(userKey);
                if (members == null || members.isEmpty()) {
                    continue;
                }
                List<String> dead = new ArrayList<>();
                for (String m : members) {
                    if (m.startsWith(memberPrefix)) {
                        dead.add(m);
                    }
                }
                if (!dead.isEmpty()) {
                    futures.add(async.srem(userKey, dead.toArray(new String[0])).toCompletableFuture());
                    prunedUsers++;
                }
            }
            if (!futures.isEmpty()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
            log.info("Removed dead node {} from {} user session set(s)", nodeId, prunedUsers);
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
        log.info("RedisUserRegistry shut down");
    }

    // ---- Key / member helpers ----

    /** Base64url (no padding) — delimiter-safe (no ':' / '|' / '{' / '}'). */
    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64decode(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    /** User set key, hash-tagged on {@code b64userId} for Redis-Cluster slot co-location. */
    private static String userKey(String userId) {
        return USER_PREFIX + "{" + b64(userId) + "}";
    }

    /** Member format: {@code nodeId|b64uri|sessionId} (nodeId leading so removeAllForNode can prefix-match). */
    private static String memberOf(String nodeId, String uri, String sessionId) {
        return nodeId + "|" + b64(uri) + "|" + sessionId;
    }

    /** Parses a member {@code nodeId|b64uri|sessionId} into a {@link SessionRef}; null if malformed. */
    private static SessionRef parseMember(String member) {
        int first = member.indexOf('|');
        if (first <= 0) {
            return null;
        }
        int second = member.indexOf('|', first + 1);
        if (second <= first || second == member.length() - 1) {
            return null;
        }
        String nodeId = member.substring(0, first);
        String b64uri = member.substring(first + 1, second);
        String sessionId = member.substring(second + 1);
        String uri;
        try {
            uri = b64decode(b64uri);
        } catch (IllegalArgumentException e) {
            return null;
        }
        return new SessionRef(nodeId, uri, sessionId);
    }
}
