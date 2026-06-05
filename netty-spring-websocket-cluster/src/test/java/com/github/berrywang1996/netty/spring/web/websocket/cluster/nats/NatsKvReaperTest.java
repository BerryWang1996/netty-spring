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

import io.nats.client.JetStreamApiException;
import io.nats.client.KeyValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for {@link NatsKvReaper} over a mocked {@link KeyValue}: a successful KV {@code create} wins the
 * claim; a {@link JetStreamApiException} (what create-on-existing-key throws, apiErrorCode 10071) means another
 * node already claimed, so this caller loses. The real two-claimant single-winner path is proven by the IT.
 */
class NatsKvReaperTest {

    private KeyValue kv;
    private NatsKvReaper reaper;

    @BeforeEach
    void setUp() {
        kv = mock(KeyValue.class);
        reaper = new NatsKvReaper(kv);
    }

    @Test
    void tryClaim_createSucceeds_wins_andUsesReapingKey() throws Exception {
        when(kv.create(any(), any(byte[].class))).thenReturn(1L);

        assertTrue(reaper.tryClaim("dead", "me", 5000));
        verify(kv).create(eq("r.dead"), any(byte[].class));
    }

    @Test
    void tryClaim_createThrowsExists_loses() throws Exception {
        // create() on an existing key throws JetStreamApiException (wrong-last-sequence / key exists).
        when(kv.create(any(), any(byte[].class))).thenThrow(mock(JetStreamApiException.class));

        assertFalse(reaper.tryClaim("dead", "other", 5000));
    }
}
