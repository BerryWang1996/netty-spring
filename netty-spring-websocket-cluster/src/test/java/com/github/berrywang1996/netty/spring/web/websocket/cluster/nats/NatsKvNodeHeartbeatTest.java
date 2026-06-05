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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link NatsKvNodeHeartbeat} over a mocked {@link KeyValue}: register writes a timestamp under
 * the raw nodeId key, and {@code findExpiredNodes} reports a node whose stored timestamp is older than the
 * timeout while excluding a freshly-written one.
 */
class NatsKvNodeHeartbeatTest {

    private KeyValue kv;
    private NatsKvNodeHeartbeat hb;

    @BeforeEach
    void setUp() {
        kv = mock(KeyValue.class);
        hb = new NatsKvNodeHeartbeat(kv);
    }

    @Test
    void register_putsTimestampUnderRawNodeIdKey() throws Exception {
        hb.register("n1", 10000);
        verify(kv).put(eq("n1"), any(byte[].class));
    }

    @Test
    void findExpiredNodes_staleDetected_freshExcluded() throws Exception {
        long now = System.currentTimeMillis();
        // Build the entry stubs FIRST as locals — entry() itself calls when(...), and nesting that
        // inside an outer when().thenReturn() corrupts Mockito's stubbing state (UnfinishedStubbing).
        KeyValueEntry staleEntry = entry(String.valueOf(now - 60000));
        KeyValueEntry freshEntry = entry(String.valueOf(now));
        when(kv.keys()).thenReturn(List.of("stale", "fresh"));
        when(kv.get("stale")).thenReturn(staleEntry);
        when(kv.get("fresh")).thenReturn(freshEntry);

        List<String> expired = hb.findExpiredNodes(10000);

        assertTrue(expired.contains("stale"), "node with an aged timestamp is expired");
        assertFalse(expired.contains("fresh"), "freshly-written node is not expired");
    }

    private static KeyValueEntry entry(String value) {
        KeyValueEntry e = mock(KeyValueEntry.class);
        when(e.getValueAsString()).thenReturn(value);
        when(e.getValue()).thenReturn(value.getBytes(StandardCharsets.UTF_8));
        return e;
    }
}
