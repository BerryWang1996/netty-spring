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

import io.nats.client.KeyValue;
import io.nats.client.api.KeyValueEntry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link NatsKvSessionRegistry} over a mocked {@link KeyValue}. Asserts the NATS-KV-legal key
 * scheme ({@code s.<b64url(uri)>.<sessionId>} + the {@code n.…} membership key) and that deregister is the
 * plain read-then-delete pair (no atomic/eval-like op — there is none on KV).
 */
class NatsKvSessionRegistryTest {

    private KeyValue kv;
    private NatsKvSessionRegistry registry;

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        kv = mock(KeyValue.class);
        registry = new NatsKvSessionRegistry(kv);
    }

    @Test
    void register_putsSessionKeyAndMemberKey() throws Exception {
        registry.register("/ws/x", "s1", "node-A", Collections.emptyMap())
                .toCompletableFuture().join();

        verify(kv).put(eq("s." + b64("/ws/x") + ".s1"), any(byte[].class));
        verify(kv).put(eq("n." + b64("node-A") + "." + b64("/ws/x") + ".s1"), any(byte[].class));
    }

    @Test
    void deregister_getsOwnerThenDeletesBothKeys_noAtomicOp() throws Exception {
        KeyValueEntry entry = mock(KeyValueEntry.class);
        when(entry.getValueAsString()).thenReturn("node-A");
        when(kv.get(eq("s." + b64("/ws/x") + ".s1"))).thenReturn(entry);

        registry.deregister("/ws/x", "s1").toCompletableFuture().join();

        // Owner looked up first, then BOTH keys deleted as separate ops.
        verify(kv).get("s." + b64("/ws/x") + ".s1");
        verify(kv).delete("s." + b64("/ws/x") + ".s1");
        verify(kv).delete("n." + b64("node-A") + "." + b64("/ws/x") + ".s1");
        // No create/update sneaking in — deregister is pure get + delete (KV has no atomic CAS-delete used here).
        verify(kv, never()).create(any(), any(byte[].class));
        verify(kv, never()).update(any(), any(byte[].class), org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void deregister_missingSession_getOnly_noDelete() throws Exception {
        when(kv.get(any())).thenReturn(null);

        registry.deregister("/ws/x", "missing").toCompletableFuture().join();

        verify(kv).get("s." + b64("/ws/x") + ".missing");
        verify(kv, never()).delete(any());
    }

    @Test
    void lookupNode_returnsStoredNodeId() throws Exception {
        KeyValueEntry entry = mock(KeyValueEntry.class);
        when(entry.getValueAsString()).thenReturn("node-A");
        when(kv.get(eq("s." + b64("/ws/x") + ".s1"))).thenReturn(entry);

        assertEquals("node-A", registry.lookupNode("/ws/x", "s1").toCompletableFuture().join());
    }

    @Test
    void lookupNode_absent_returnsNull() throws Exception {
        when(kv.get(any())).thenReturn(null);

        assertNull(registry.lookupNode("/ws/x", "nope").toCompletableFuture().join());
    }

    @Test
    void asyncOpsRunOnDedicatedNamedThread_notCommonPool() throws Exception {
        // The blocking KV op must NOT run on ForkJoinPool.commonPool(); it must run on the registry's
        // dedicated, named pool so it cannot starve the shared common pool on the unicast hot path.
        java.util.concurrent.atomic.AtomicReference<String> threadName = new java.util.concurrent.atomic.AtomicReference<>();
        when(kv.get(any())).thenAnswer(inv -> {
            threadName.set(Thread.currentThread().getName());
            return null;
        });

        registry.lookupNode("/ws/x", "s1").toCompletableFuture().join();

        org.junit.jupiter.api.Assertions.assertNotNull(threadName.get());
        assertTrue(threadName.get().startsWith("nats-kv-registry-"),
                "blocking KV op ran on '" + threadName.get() + "' — expected the dedicated nats-kv-registry pool");
    }
}
