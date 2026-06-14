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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterRoomRegistry;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Redis implementation of {@link ClusterRoomRegistry} — the per-room node-set routing primitive (1.10.0).
 *
 * <p><b>Redis key design</b> (URI + room base64url-encoded so a ':'-containing URI/room that prefixes
 * another cannot leak/extract the wrong sets — base64url is delimiter-safe). The per-room keys share a
 * Redis-Cluster hash tag {@code {b64uri:b64room}} so the node-set and the per-node member sets for one room
 * co-locate on a single slot (the JOIN/LEAVE Lua is single-slot):
 * <ul>
 *   <li>{@code netty:room:{b64uri:b64room}:nodes} → Set&lt;nodeId&gt;: nodes hosting ≥1 member. <b>The routing key.</b></li>
 *   <li>{@code netty:room:{b64uri:b64room}:n:{nodeId}} → Set&lt;sessionId&gt;: this room's members on a node
 *       (the per-node refcount that lets us know when a node drops to 0 members → leaves the node-set).</li>
 *   <li>{@code netty:roomsession:{b64uri}:{sessionId}} → Set&lt;room&gt;: rooms a session is in (for removeAllForSession).</li>
 * </ul>
 *
 * <p><b>Atomicity</b>: join / leave / removeAllForSession each run as a single {@code EVAL} (no looped
 * SREMs), mirroring the RC11 atomic-deregister pattern in {@code RedisSessionRegistry}. The node-set
 * add-on-first / remove-on-last transition is decided inside the script (read the per-node member set's
 * cardinality), so concurrent joins/leaves can't corrupt the node-set.
 *
 * <p><b>Local index</b> (per node, in-process): serves {@link #localMembers} / {@link #roomsForSession} on
 * the receive hot path with zero I/O, and is updated <b>after</b> the Lua call confirms so the local index
 * never claims a membership Redis rejected.
 *
 * <p><b>Threading</b>: the blocking Lettuce calls run on a small dedicated executor (mirrors the rationale
 * of {@code NatsKvSessionRegistry}) so the room control path never borrows a transport I/O thread.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
@Slf4j
public class RedisRoomRegistry implements ClusterRoomRegistry {

    private static final String ROOM_PREFIX = "netty:room:";
    private static final String ROOM_SESSION_PREFIX = "netty:roomsession:";

    /**
     * JOIN: KEYS[1]=per-node member set, KEYS[2]=node-set, KEYS[3]=roomsession set.
     * ARGV[1]=sessionId, ARGV[2]=nodeId, ARGV[3]=room.
     * SADD the member; if that per-node set was empty before → SADD node to the node-set. Track the room
     * for the session. Single EVAL. Returns 1 if the node was newly added to the node-set, else 0.
     */
    private static final String JOIN_LUA =
            "local before = redis.call('SCARD', KEYS[1]) "
          + "redis.call('SADD', KEYS[1], ARGV[1]) "
          + "local added = 0 "
          + "if before == 0 then "
          + "  redis.call('SADD', KEYS[2], ARGV[2]) "
          + "  added = 1 "
          + "end "
          + "redis.call('SADD', KEYS[3], ARGV[3]) "
          + "return added";

    /**
     * LEAVE: KEYS[1]=per-node member set, KEYS[2]=node-set, KEYS[3]=roomsession set.
     * ARGV[1]=sessionId, ARGV[2]=nodeId, ARGV[3]=room.
     * SREM the member; if the per-node set is now empty → SREM node from the node-set + DEL the empty set.
     * SREM the room from the session's room set. Single EVAL. Returns 1 if the node was removed, else 0.
     */
    private static final String LEAVE_LUA =
            "redis.call('SREM', KEYS[1], ARGV[1]) "
          + "local removed = 0 "
          + "if redis.call('SCARD', KEYS[1]) == 0 then "
          + "  redis.call('SREM', KEYS[2], ARGV[2]) "
          + "  redis.call('DEL', KEYS[1]) "
          + "  removed = 1 "
          + "end "
          + "redis.call('SREM', KEYS[3], ARGV[3]) "
          + "return removed";

    /**
     * REMOVE_ALL_FOR_SESSION: KEYS[1]=roomsession set. ARGV[1]=base64url(uri), ARGV[2]=sessionId,
     * ARGV[3]=nodeId. Reads the session's rooms, and for each: SREM from that room's per-node member set,
     * and if it became empty SREM the node from the room's node-set + DEL the set. Then DEL roomsession.
     * Single EVAL (bounded by the session's room count). Key names are reconstructed inside the script from
     * ARGV[1] (b64uri) + each room (already base64url-encoded by the caller via a parallel set, see below).
     *
     * <p>The room values stored in the roomsession set are the RAW room strings; the script base64url-encodes
     * is NOT available in Lua, so we instead store the per-room key SUFFIX (b64room) is computed Java-side and
     * passed as the room token. To keep the script self-contained we store the b64room token in roomsession
     * (not the raw room) — see {@link #b64} usage in {@link #join}.
     */
    private static final String REMOVE_ALL_FOR_SESSION_LUA =
            "local rooms = redis.call('SMEMBERS', KEYS[1]) "
          + "for _, b64room in ipairs(rooms) do "
          + "  local tag = '{' .. ARGV[1] .. ':' .. b64room .. '}' "
          + "  local memberKey = 'netty:room:' .. tag .. ':n:' .. ARGV[3] "
          + "  local nodesKey = 'netty:room:' .. tag .. ':nodes' "
          + "  redis.call('SREM', memberKey, ARGV[2]) "
          + "  if redis.call('SCARD', memberKey) == 0 then "
          + "    redis.call('SREM', nodesKey, ARGV[3]) "
          + "    redis.call('DEL', memberKey) "
          + "  end "
          + "end "
          + "redis.call('DEL', KEYS[1]) "
          + "return #rooms";

    private final StatefulRedisConnection<String, String> connection;
    private final ExecutorService executor;

    // ---- Local index (per node, in-process) ----
    /** (uri,room) → local member sessionIds on THIS node. */
    private final ConcurrentHashMap<String, Set<String>> localMembers = new ConcurrentHashMap<>();
    /** (uri,sessionId) → rooms this local session is in. */
    private final ConcurrentHashMap<String, Set<String>> roomsBySession = new ConcurrentHashMap<>();

    public RedisRoomRegistry(StatefulRedisConnection<String, String> connection) {
        this.connection = connection;
        final AtomicInteger n = new AtomicInteger();
        this.executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "cluster-room-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
    }

    @Override
    public CompletionStage<Void> join(String uri, String room, String sessionId, String nodeId) {
        String b64uri = b64(uri);
        String b64room = b64(room);
        String tag = "{" + b64uri + ":" + b64room + "}";
        String memberKey = ROOM_PREFIX + tag + ":n:" + nodeId;
        String nodesKey = ROOM_PREFIX + tag + ":nodes";
        String roomSessionKey = ROOM_SESSION_PREFIX + b64uri + ":" + sessionId;

        RedisAsyncCommands<String, String> async = connection.async();
        // The roomsession set stores the b64room token (not the raw room) so the removeAllForSession Lua can
        // reconstruct each room's keys without needing base64 in Lua.
        return async.<Long>eval(JOIN_LUA, ScriptOutputType.INTEGER,
                        new String[]{ memberKey, nodesKey, roomSessionKey }, sessionId, nodeId, b64room)
                .thenAccept(added -> {
                    // Local index updated AFTER the Lua confirms (never claim membership Redis rejected).
                    localMembers.computeIfAbsent(localKey(uri, room), k -> ConcurrentHashMap.newKeySet()).add(sessionId);
                    roomsBySession.computeIfAbsent(localKey(uri, sessionId), k -> ConcurrentHashMap.newKeySet()).add(room);
                    log.debug("Session {} joined room {} on URI {} (node {}, node-set-added={})",
                            sessionId, room, uri, nodeId, added);
                })
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Void> leave(String uri, String room, String sessionId, String nodeId) {
        String b64uri = b64(uri);
        String b64room = b64(room);
        String tag = "{" + b64uri + ":" + b64room + "}";
        String memberKey = ROOM_PREFIX + tag + ":n:" + nodeId;
        String nodesKey = ROOM_PREFIX + tag + ":nodes";
        String roomSessionKey = ROOM_SESSION_PREFIX + b64uri + ":" + sessionId;

        RedisAsyncCommands<String, String> async = connection.async();
        return async.<Long>eval(LEAVE_LUA, ScriptOutputType.INTEGER,
                        new String[]{ memberKey, nodesKey, roomSessionKey }, sessionId, nodeId, b64room)
                .thenAccept(removed -> {
                    removeLocalMember(uri, room, sessionId);
                    log.debug("Session {} left room {} on URI {} (node {}, node-set-removed={})",
                            sessionId, room, uri, nodeId, removed);
                })
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Set<String>> nodesForRoom(String uri, String room) {
        String tag = "{" + b64(uri) + ":" + b64(room) + "}";
        String nodesKey = ROOM_PREFIX + tag + ":nodes";
        return connection.async().smembers(nodesKey)
                .thenApply(members -> members == null ? Collections.<String>emptySet() : members)
                .toCompletableFuture();
    }

    @Override
    public Set<String> localMembers(String uri, String room) {
        Set<String> m = localMembers.get(localKey(uri, room));
        return m == null ? Collections.emptySet() : new HashSet<>(m);
    }

    @Override
    public Set<String> roomsForSession(String uri, String sessionId) {
        Set<String> r = roomsBySession.get(localKey(uri, sessionId));
        return r == null ? Collections.emptySet() : new HashSet<>(r);
    }

    @Override
    public CompletionStage<Void> removeAllForSession(String uri, String sessionId, String nodeId) {
        String b64uri = b64(uri);
        String roomSessionKey = ROOM_SESSION_PREFIX + b64uri + ":" + sessionId;

        RedisAsyncCommands<String, String> async = connection.async();
        return async.<Long>eval(REMOVE_ALL_FOR_SESSION_LUA, ScriptOutputType.INTEGER,
                        new String[]{ roomSessionKey }, b64uri, sessionId, nodeId)
                .thenAccept(cnt -> {
                    // Clear the local index for this session across all its rooms.
                    Set<String> rooms = roomsBySession.remove(localKey(uri, sessionId));
                    if (rooms != null) {
                        for (String room : rooms) {
                            removeLocalMemberOnly(uri, room, sessionId);
                        }
                    }
                    log.debug("Removed session {} from all {} room(s) on URI {} (node {})",
                            sessionId, cnt, uri, nodeId);
                })
                .toCompletableFuture();
    }

    @Override
    public CompletionStage<Void> removeAllForNode(String nodeId) {
        // SCAN every per-node member set owned by nodeId (netty:room:{...}:n:{nodeId}); for each, pull nodeId
        // from the room's node-set and DEL the member set. Pipelined; SCAN keeps the Redis event loop free.
        return CompletableFuture.runAsync(() -> {
            RedisCommands<String, String> sync = connection.sync();
            String suffix = ":n:" + nodeId;
            String pattern = ROOM_PREFIX + "*" + suffix;
            ScanCursor cursor = ScanCursor.INITIAL;
            ScanArgs args = ScanArgs.Builder.matches(pattern).limit(100);
            List<String> memberKeys = new ArrayList<>();
            do {
                var scan = sync.scan(cursor, args);
                for (String key : scan.getKeys()) {
                    if (key.startsWith(ROOM_PREFIX) && key.endsWith(suffix)) {
                        memberKeys.add(key);
                    }
                }
                cursor = scan;
            } while (!cursor.isFinished());

            RedisAsyncCommands<String, String> async = connection.async();
            List<CompletableFuture<?>> futures = new ArrayList<>();
            for (String memberKey : memberKeys) {
                // memberKey = netty:room:{b64uri:b64room}:n:{nodeId} → derive the room's nodes key.
                String nodesKey = memberKey.substring(0, memberKey.length() - suffix.length()) + ":nodes";
                futures.add(async.srem(nodesKey, nodeId).toCompletableFuture());
                futures.add(async.del(memberKey).toCompletableFuture());
            }
            if (!futures.isEmpty()) {
                CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            }
            log.info("Removed dead node {} from {} room member set(s)", nodeId, memberKeys.size());
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
        localMembers.clear();
        roomsBySession.clear();
        log.info("RedisRoomRegistry shut down");
    }

    // ---- Local index helpers ----

    private void removeLocalMember(String uri, String room, String sessionId) {
        removeLocalMemberOnly(uri, room, sessionId);
        Set<String> rooms = roomsBySession.get(localKey(uri, sessionId));
        if (rooms != null) {
            rooms.remove(room);
            if (rooms.isEmpty()) {
                roomsBySession.remove(localKey(uri, sessionId));
            }
        }
    }

    private void removeLocalMemberOnly(String uri, String room, String sessionId) {
        Set<String> m = localMembers.get(localKey(uri, room));
        if (m != null) {
            m.remove(sessionId);
            if (m.isEmpty()) {
                localMembers.remove(localKey(uri, room));
            }
        }
    }

    // ---- Key helpers ----

    /** Base64url (no padding) — delimiter-safe (no ':' / '|' / '{' / '}'), so a prefixing URI/room
     *  cannot produce an ambiguous key or hash tag. */
    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** In-process local-index key (uses a space separator that cannot appear in a URI). */
    private static String localKey(String a, String b) {
        return a + " " + b;
    }
}
