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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.codec;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The RC1 correctness gate: a 1.9.0 (v1) node and a 1.10.0 (v2) node share one cluster, so the wire
 * format must be rolling-upgrade-safe in BOTH directions.
 *
 * <p>Approach (per spec §7): {@code room} is appended as the second-to-last wire field (payload stays
 * the trailing rest-of-line field) and the codec decodes <b>version-aware</b> by counting actual fields,
 * so no leading discriminator is needed and the v1 format is byte-for-byte unchanged when {@code room}
 * is absent. This test proves:
 * <ol>
 *   <li>v2 codec encode → v2 codec decode round-trips the room (and the no-room case stays v1-shaped);</li>
 *   <li>v2 codec decodes a hand-built v1 wire (8 fields, version 1) → room=null, no error;</li>
 *   <li>a v1 decoder (simulated with the exact 1.9.0 split-then-version-check logic) decoding a real v2
 *       wire DISCARDS on the version gate and never throws — the load-bearing safety property.</li>
 * </ol>
 */
class EnvelopeRollingUpgradeTest {

    private final SimpleTextEnvelopeCodec codec = new SimpleTextEnvelopeCodec();

    @Test
    void currentVersionAndFieldCountAreCoBumped() {
        // The whole compat story hinges on these two moving in lockstep (spec §7).
        assertEquals(2, ClusterEnvelope.CURRENT_VERSION, "CURRENT_VERSION must be 2 for the room field");
        assertEquals(9, SimpleTextEnvelopeCodec.FIELD_COUNT, "v2 wire must carry 9 fields");
        assertEquals(8, SimpleTextEnvelopeCodec.FIELD_COUNT_V1, "v1 wire field count kept for read-compat");
    }

    @Test
    void v2RoundTripsRoom() {
        ClusterEnvelope env = new ClusterEnvelope(
                "node-A", "/ws/chat", ClusterEnvelope.MessageKind.ROOM_BROADCAST,
                "T:hi room".getBytes(StandardCharsets.UTF_8),
                null, null, 12345L, "room-42");

        String wire = codec.encode(env);
        // version must be the leading field so a v1 splitter can still read it (discard gate).
        assertTrue(wire.startsWith("2|"), "v2 wire must lead with version token 2: " + wire);

        ClusterEnvelope back = codec.decode(wire);
        assertNotNull(back);
        assertEquals(2, back.getVersion());
        assertEquals("node-A", back.getOriginNodeId());
        assertEquals("/ws/chat", back.getUri());
        assertEquals(ClusterEnvelope.MessageKind.ROOM_BROADCAST, back.getKind());
        assertEquals("room-42", back.getRoom());
        assertEquals("T:hi room", new String(back.getPayload(), StandardCharsets.UTF_8));
        assertEquals(12345L, back.getTimestamp());
    }

    @Test
    void v2NoRoomDecodesRoomNull() {
        ClusterEnvelope env = new ClusterEnvelope(
                "node-A", "/ws/chat", ClusterEnvelope.MessageKind.BROADCAST,
                "T:plain".getBytes(StandardCharsets.UTF_8),
                null, null, 7L);
        ClusterEnvelope back = codec.decode(codec.encode(env));
        assertNotNull(back);
        assertNull(back.getRoom(), "a BROADCAST envelope carries no room");
        assertEquals(ClusterEnvelope.MessageKind.BROADCAST, back.getKind());
    }

    @Test
    void v2CodecDecodesV1Wire_roomNull() {
        // A hand-built v1 wire: 8 fields, version 1, NO room field (exactly what a 1.9.0 node emits).
        String b64payload = Base64.getEncoder().encodeToString("T:legacy".getBytes(StandardCharsets.UTF_8));
        String v1wire = "1|node-old|/ws/chat|BROADCAST|||9999|" + b64payload; // 8 fields

        ClusterEnvelope back = codec.decode(v1wire);
        assertNotNull(back, "v2 codec must accept a v1 wire, not discard it");
        assertEquals(1, back.getVersion());
        assertEquals("node-old", back.getOriginNodeId());
        assertEquals(ClusterEnvelope.MessageKind.BROADCAST, back.getKind());
        assertNull(back.getRoom(), "v1 wire has no room → null");
        assertEquals("T:legacy", new String(back.getPayload(), StandardCharsets.UTF_8));
        assertEquals(9999L, back.getTimestamp());
    }

    @Test
    void v1CodecDecodingV2Wire_discardsOnVersion_noCrash() {
        // Produce a REAL v2 wire from the production codec.
        ClusterEnvelope env = new ClusterEnvelope(
                "node-A", "/ws/chat", ClusterEnvelope.MessageKind.ROOM_BROADCAST,
                "T:hi".getBytes(StandardCharsets.UTF_8), null, null, 1L, "room-x");
        String v2wire = codec.encode(env);

        // Decode it with the EXACT 1.9.0 (v1) codec logic: split on a fixed 8-field count, read the
        // version from field 0, and discard if version > 1. This is reproduced verbatim so the test
        // proves the deployed v1 node behavior — discard-on-version, never throw.
        ClusterEnvelope result = decodeAsV1Codec(v2wire);
        assertNull(result, "a v1 node must DISCARD a v2 wire on the version gate (returns null, no exception)");
    }

    /**
     * Byte-for-byte reproduction of the 1.9.0 {@code SimpleTextEnvelopeCodec.decode} (FIELD_COUNT=8,
     * CURRENT_VERSION=1): fixed-count split, then version check. With a v2 wire (9 fields) the 8-field
     * splitter collapses {@code room|payload} into the trailing field, but the version token (field 0)
     * is read first and "2 > 1" trips the discard gate BEFORE any Base64 decode — so no exception is
     * thrown. This is the load-bearing rolling-upgrade guarantee.
     */
    private static ClusterEnvelope decodeAsV1Codec(String data) {
        final int v1FieldCount = 8;
        final int v1CurrentVersion = 1;
        String[] parts = new String[v1FieldCount];
        int fieldIndex = 0;
        int start = 0;
        for (int i = 0; i < data.length() && fieldIndex < v1FieldCount - 1; i++) {
            if (data.charAt(i) == '|') {
                parts[fieldIndex++] = data.substring(start, i);
                start = i + 1;
            }
        }
        parts[fieldIndex] = data.substring(start);
        if (fieldIndex < v1FieldCount - 1) {
            throw new IllegalStateException("malformed");
        }
        int version = Integer.parseInt(parts[0]);
        if (version > v1CurrentVersion) {
            return null; // discard — exactly what the 1.9.0 node does
        }
        // (v1 path would decode here — not reached for a v2 wire)
        byte[] payload = Base64.getDecoder().decode(parts[7]);
        return new ClusterEnvelope(version, parts[1], parts[2],
                ClusterEnvelope.MessageKind.valueOf(parts[3]),
                payload, parts[4].isEmpty() ? null : parts[4],
                parts[5].isEmpty() ? null : parts[5], Long.parseLong(parts[6]));
    }
}
