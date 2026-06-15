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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceRegistry;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceStatus;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.PresenceTransition;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.UserPresence;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis implementation of {@link PresenceRegistry} (1.10.0-RC3) — per-user multi-device presence with atomic
 * Lua transition detection.
 *
 * <p><b>Key design</b> (userId base64url-encoded — delimiter-safe; hash tag {@code {b64userId}} pins a user's
 * presence to a single Redis-Cluster slot, co-located with {@code netty:user:{b64userId}}):
 * <ul>
 *   <li>{@code netty:presence:{b64userId}} → HASH, field {@code nodeId|sessionId} (nodeId leading so the dead-node
 *       reap can prefix-match), value {@code ONLINE} / {@code AWAY}. Aggregate is computable from {@code HVALS}.</li>
 * </ul>
 *
 * <p><b>Atomic transition detection</b>: every mutation runs a single Lua {@code EVAL} on the one hash that reads the
 * old aggregate, applies the change, recomputes the new aggregate, and returns {@code {old, new}}. Lua serializes
 * concurrent multi-node mutations on the single slot, so two simultaneous first-connections yield exactly one
 * {@code OFFLINE -> ONLINE} transition.
 *
 * <p><b>Reads</b> ({@link #getPresence}) hit {@code HVALS} every call (NOT cached — the anti-false-online contract).
 * The blocking {@code removeAllForNode} SCAN runs on a small dedicated executor (mirrors {@code RedisUserRegistry}).
 *
 * @author berrywang1996
 * @since V1.10.0
 */
@Slf4j
public class RedisPresenceRegistry implements PresenceRegistry {

    static final String PRESENCE_PREFIX = "netty:presence:";

    /** Shared Lua aggregate function prelude (inlined into every script). */
    private static final String AGG =
            "local function agg(k)\n" +
            "  local vals = redis.call('HVALS', k)\n" +
            "  if #vals == 0 then return 'OFFLINE' end\n" +
            "  for _,v in ipairs(vals) do if v == 'ONLINE' then return 'ONLINE' end end\n" +
            "  return 'AWAY'\n" +
            "end\n";

    /** KEYS[1]=hashKey; ARGV[1]=field; ARGV[2]=status. Returns {old, new}. */
    public static final String SET_LUA = AGG +
            "local old = agg(KEYS[1])\n" +
            "redis.call('HSET', KEYS[1], ARGV[1], ARGV[2])\n" +
            "local new = agg(KEYS[1])\n" +
            "return {old, new}";

    /** KEYS[1]=hashKey; ARGV[1]=status. Sets ALL fields to status. Returns {old, new}. */
    public static final String SET_USER_LUA = AGG +
            "local old = agg(KEYS[1])\n" +
            "local fields = redis.call('HKEYS', KEYS[1])\n" +
            "for _,f in ipairs(fields) do redis.call('HSET', KEYS[1], f, ARGV[1]) end\n" +
            "local new = agg(KEYS[1])\n" +
            "return {old, new}";

    /** KEYS[1]=hashKey; ARGV[1]=field. HDEL one field; DEL key if empty. Returns {old, new}. */
    public static final String CLEAR_LUA = AGG +
            "local old = agg(KEYS[1])\n" +
            "redis.call('HDEL', KEYS[1], ARGV[1])\n" +
            "local new = agg(KEYS[1])\n" +
            "if redis.call('HLEN', KEYS[1]) == 0 then redis.call('DEL', KEYS[1]) end\n" +
            "return {old, new}";

    /** KEYS[1]=hashKey; ARGV[1]=nodePrefix ("nodeId|"). HDEL fields starting with the prefix. Returns {old, new}. */
    public static final String REAP_LUA = AGG +
            "local old = agg(KEYS[1])\n" +
            "local fields = redis.call('HKEYS', KEYS[1])\n" +
            "local p = ARGV[1]\n" +
            "for _,f in ipairs(fields) do\n" +
            "  if string.sub(f, 1, string.len(p)) == p then redis.call('HDEL', KEYS[1], f) end\n" +
            "end\n" +
            "local new = agg(KEYS[1])\n" +
            "if redis.call('HLEN', KEYS[1]) == 0 then redis.call('DEL', KEYS[1]) end\n" +
            "return {old, new}";

    private final StatefulRedisConnection<String, String> connection;
    private final ExecutorService executor;

    public RedisPresenceRegistry(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
        final AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cluster-presence-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletionStage<PresenceTransition> setPresence(String userId, String nodeId, String sessionId, PresenceStatus status) {
        RedisFutureEval eval = evalMulti(SET_LUA, key(userId), field(nodeId, sessionId), status.name());
        return eval.asTransition(null);
    }

    @Override
    public CompletionStage<PresenceTransition> setPresenceForUser(String userId, PresenceStatus status) {
        RedisFutureEval eval = evalMulti(SET_USER_LUA, key(userId), status.name());
        return eval.asTransition(null);
    }

    @Override
    public CompletionStage<PresenceTransition> clearPresence(String userId, String nodeId, String sessionId) {
        RedisFutureEval eval = evalMulti(CLEAR_LUA, key(userId), field(nodeId, sessionId));
        return eval.asTransition(null);
    }

    @Override
    public CompletionStage<UserPresence> getPresence(String userId) {
        // NOT cached — fresh HVALS every call.
        return connection.async().hvals(key(userId))
                .thenApply(vals -> {
                    int online = 0;
                    int away = 0;
                    if (vals != null) {
                        for (String v : vals) {
                            if (PresenceStatus.ONLINE.name().equals(v)) {
                                online++;
                            } else {
                                away++;
                            }
                        }
                    }
                    return new UserPresence(UserPresence.aggregateOf(online, away), online, away);
                })
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<List<PresenceTransition>> removeAllForNode(String nodeId) {
        return CompletableFuture.supplyAsync(() -> {
            RedisCommands<String, String> sync = connection.sync();
            String pattern = PRESENCE_PREFIX + "*";
            String prefix = nodeId + "|";
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs args = ScanArgs.Builder.matches(pattern).limit(100);
            List<String> presenceKeys = new ArrayList<>();
            do {
                var scan = sync.scan(cursor, args);
                for (String k : scan.getKeys()) {
                    if (k.startsWith(PRESENCE_PREFIX)) {
                        presenceKeys.add(k);
                    }
                }
                cursor = scan;
            } while (!cursor.isFinished());

            List<PresenceTransition> changed = new ArrayList<>();
            for (String k : presenceKeys) {
                @SuppressWarnings("unchecked")
                List<Object> res = (List<Object>) sync.eval(REAP_LUA, ScriptOutputType.MULTI,
                        new String[]{k}, prefix);
                PresenceStatus old = PresenceStatus.valueOf(String.valueOf(res.get(0)));
                PresenceStatus now = PresenceStatus.valueOf(String.valueOf(res.get(1)));
                if (old != now) {
                    changed.add(new PresenceTransition(userIdOf(k), old, now));
                }
            }
            if (!changed.isEmpty()) {
                log.info("Reaped dead node {} from presence — {} user(s) transitioned", nodeId, changed.size());
            }
            return changed;
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
        log.info("RedisPresenceRegistry shut down");
    }

    // ---- eval helper ----

    private RedisFutureEval evalMulti(String script, String key, String... argv) {
        return new RedisFutureEval(connection.async().eval(script, ScriptOutputType.MULTI, new String[]{key}, argv));
    }

    /** Wraps the eval RedisFuture<List<Object>> and maps {old,new} to a PresenceTransition. */
    private static final class RedisFutureEval {
        private final io.lettuce.core.RedisFuture<List<Object>> future;
        RedisFutureEval(io.lettuce.core.RedisFuture<List<Object>> future) {
            this.future = future;
        }
        CompletionStage<PresenceTransition> asTransition(String userId) {
            return future.thenApply(res -> {
                PresenceStatus old = PresenceStatus.valueOf(String.valueOf(res.get(0)));
                PresenceStatus now = PresenceStatus.valueOf(String.valueOf(res.get(1)));
                return new PresenceTransition(userId, old, now);
            }).toCompletableFuture();
        }
    }

    // ---- key / field helpers ----

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64decode(String s) {
        return new String(Base64.getUrlDecoder().decode(s), StandardCharsets.UTF_8);
    }

    /** Presence hash key, hash-tagged on {@code b64userId}. */
    static String key(String userId) {
        return PRESENCE_PREFIX + "{" + b64(userId) + "}";
    }

    /** Field format {@code nodeId|sessionId} (nodeId leading so reap can prefix-match). */
    private static String field(String nodeId, String sessionId) {
        return nodeId + "|" + sessionId;
    }

    /** Decodes the userId from a presence key {@code netty:presence:{b64userId}}. */
    private static String userIdOf(String presenceKey) {
        int lb = presenceKey.indexOf('{');
        int rb = presenceKey.lastIndexOf('}');
        if (lb < 0 || rb <= lb) {
            return presenceKey;
        }
        try {
            return b64decode(presenceKey.substring(lb + 1, rb));
        } catch (IllegalArgumentException e) {
            return presenceKey;
        }
    }
}
