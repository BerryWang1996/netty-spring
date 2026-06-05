package com.github.berrywang1996.netty.spring.web.websocket.cluster.redis;

import io.lettuce.core.cluster.api.StatefulRedisClusterConnection;
import io.lettuce.core.cluster.api.async.RedisAdvancedClusterAsyncCommands;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit test for {@link RedisClusterModeSessionRegistry}: asserts the cluster impl issues the expected
 * slot-routed commands and — crucially — that {@code deregister} is NON-ATOMIC (HGET then DEL + SREM,
 * never an EVAL, which would be CROSSSLOT in Redis Cluster).
 */
class RedisClusterModeSessionRegistryTest {

    @SuppressWarnings("unchecked")
    private final RedisAdvancedClusterAsyncCommands<String, String> async =
            mock(RedisAdvancedClusterAsyncCommands.class);

    @SuppressWarnings("unchecked")
    private final StatefulRedisClusterConnection<String, String> connection =
            mock(StatefulRedisClusterConnection.class);

    private RedisClusterModeSessionRegistry registry;

    @BeforeEach
    void setUp() {
        when(connection.async()).thenReturn(async);
        registry = new RedisClusterModeSessionRegistry(connection);
    }

    @Test
    void registerIssuesHmsetAndSadd() {
        // Build the RedisFuture stubs FIRST (rf() itself does when(...) on a fresh mock); nesting a
        // when() inside an outer when().thenReturn() corrupts Mockito's stubbing state ("Unfinished
        // stubbing"), so each rf(...) must be a completed local before it is handed to thenReturn().
        io.lettuce.core.RedisFuture<String> hmsetFuture = rf("OK");
        io.lettuce.core.RedisFuture<Long> saddFuture = rf(1L);
        when(async.hmset(anyString(), any())).thenReturn(hmsetFuture);
        // sadd/srem/del take varargs (V.../K...); the registry passes a SINGLE element, so match it
        // element-wise with anyString() — any(String[].class) expects the whole array and would miss
        // a one-element vararg call (stub returns null -> NPE on toCompletableFuture()).
        when(async.sadd(anyString(), anyString())).thenReturn(saddFuture);

        registry.register("/ws/a", "sid-1", "node-A", Collections.emptyMap())
                .toCompletableFuture().join();

        // Session hash must carry the owning nodeId field. The URI token is base64url-encoded in the
        // key/member (FIX A: delimiter-safe so a ':'-containing URI prefix cannot collide).
        verify(async).hmset(eq(sessionKey("/ws/a", "sid-1")),
                argThat((Map<String, String> m) -> "node-A".equals(m.get("nodeId"))));
        // Node-set membership added under the node key, member "b64url(uri)|sessionId".
        verify(async).sadd("netty:node:node-A:sessions", member("/ws/a", "sid-1"));
        verify(async, never()).eval(anyString(), any(), any(String[].class), any());
    }

    @Test
    void deregisterIsNonAtomic_hgetThenDelAndSrem_noEval() {
        io.lettuce.core.RedisFuture<String> hgetFuture = rf("node-A");
        io.lettuce.core.RedisFuture<Long> delFuture = rf(1L);
        io.lettuce.core.RedisFuture<Long> sremFuture = rf(1L);
        when(async.hget(anyString(), eq("nodeId"))).thenReturn(hgetFuture);
        when(async.del(anyString())).thenReturn(delFuture);
        when(async.srem(anyString(), anyString())).thenReturn(sremFuture);

        registry.deregister("/ws/a", "sid-1").toCompletableFuture().join();

        // Owning node looked up first...
        verify(async).hget(sessionKey("/ws/a", "sid-1"), "nodeId");
        // ...then the session hash deleted and the node-set member removed, as SEPARATE commands.
        verify(async).del(sessionKey("/ws/a", "sid-1"));
        verify(async).srem("netty:node:node-A:sessions", member("/ws/a", "sid-1"));
        // Must NOT use a Lua EVAL (would be CROSSSLOT touching both keys in Redis Cluster).
        verify(async, never()).eval(anyString(), any(), any(String[].class), any());
    }

    @Test
    void deregisterMissingSession_hgetOnly_noDelNoSrem() {
        io.lettuce.core.RedisFuture<String> hgetFuture = rf((String) null);
        when(async.hget(anyString(), eq("nodeId"))).thenReturn(hgetFuture);

        registry.deregister("/ws/a", "sid-x").toCompletableFuture().join();

        verify(async).hget(sessionKey("/ws/a", "sid-x"), "nodeId");
        verify(async, never()).del(anyString());
        verify(async, never()).srem(anyString(), anyString());
        verify(async, never()).eval(anyString(), any(), any(String[].class), any());
    }

    /** base64url(uri) — mirrors the registry's key/member encoding (FIX A). */
    private static String b64(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
    private static String sessionKey(String uri, String sid) { return "netty:session:" + b64(uri) + ":" + sid; }
    private static String member(String uri, String sid) { return b64(uri) + "|" + sid; }

    /**
     * Stubs a Lettuce {@link io.lettuce.core.RedisFuture} backed by an already-completed
     * {@link java.util.concurrent.CompletableFuture}, delegating the chaining methods the registry
     * actually calls ({@code toCompletableFuture}, {@code thenCompose}, {@code thenAccept}).
     */
    @SuppressWarnings("unchecked")
    private static <T> io.lettuce.core.RedisFuture<T> rf(T value) {
        io.lettuce.core.RedisFuture<T> f = org.mockito.Mockito.mock(io.lettuce.core.RedisFuture.class);
        java.util.concurrent.CompletableFuture<T> cf = java.util.concurrent.CompletableFuture.completedFuture(value);
        org.mockito.Mockito.when(f.toCompletableFuture()).thenReturn(cf);
        org.mockito.Mockito.when(f.thenCompose(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> cf.thenCompose(inv.getArgument(0)));
        org.mockito.Mockito.when(f.thenAccept(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> cf.thenAccept(inv.getArgument(0)));
        return f;
    }
}
