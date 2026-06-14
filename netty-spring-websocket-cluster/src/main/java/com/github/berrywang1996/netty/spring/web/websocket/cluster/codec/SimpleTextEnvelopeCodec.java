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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBrokerException;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import lombok.extern.slf4j.Slf4j;

import java.util.Base64;

/**
 * Zero-dependency default {@link EnvelopeCodec} using a compact pipe-delimited text format.
 *
 * <p>Wire format v2 (single line, pipe-separated, 9 fields — {@code room} appended as the
 * second-to-last field so {@code payload} stays the trailing rest-of-line field):
 * <pre>
 * {version}|{originNodeId}|{uri}|{kind}|{targetSessionId}|{traceparent}|{timestamp}|{room}|{base64payload}
 * </pre>
 *
 * <p>v1 (1.8.0–1.9.x) had 8 fields and no {@code room}. This codec is <b>version-aware</b> and
 * rolling-upgrade-safe:
 * <ul>
 *   <li>A v2 codec decoding a <b>v1 wire</b> (8 fields, version 1) yields {@code room=null} — no error.</li>
 *   <li>A v2 codec decoding a <b>v2 wire</b> (9 fields, version 2) parses {@code room}.</li>
 *   <li>A v1 codec (1.9.x node) decoding a <b>v2 wire</b> discards on the {@code version &gt; max}
 *       gate (the version token is field 0, read before the payload is touched) — no crash. The v2
 *       wire keeps the version token in the leading position the v1 splitter still reads.</li>
 * </ul>
 * The decoder keys off the actual field count, so both shapes round-trip without a leading discriminator.
 *
 * <p>Nullable fields ({@code targetSessionId}, {@code traceparent}, {@code room}) are encoded as empty
 * strings between pipes. The payload is always Base64-encoded (no padding issues with pipe
 * delimiter).
 *
 * <p>This format is ~3-5x faster to encode/decode than JSON (no reflection, no tree building,
 * just split/join), and ~40% smaller on the wire (no field names, no braces/quotes).
 *
 * <p><b>Limitation:</b> field values (especially URI) must not contain the pipe character
 * ({@code |}). Per RFC 3986, the pipe character is not a valid URI character (it's in the
 * "unwise" set and should be percent-encoded). {@code @MessageMapping} URIs in practice
 * never contain pipes. If your use case requires pipe in URIs, provide a custom
 * {@link EnvelopeCodec}.
 *
 * <p>Thread-safe and allocation-minimal (StringBuilder reuse not needed — the JIT inlines
 * the string concatenation into a single StringBuilder chain).
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
public class SimpleTextEnvelopeCodec implements EnvelopeCodec {

    private static final char SEP = '|';
    /** v2 wire field count (was 8 in v1; {@code room} added). Co-bumped in lockstep with
     *  {@link ClusterEnvelope#CURRENT_VERSION} (1→2). The decoder accepts BOTH 8-field (v1) and
     *  9-field (v2) wires (version-aware), so a v2 codec reads a v1 wire and vice-versa is gated. */
    static final int FIELD_COUNT = 9;
    /** Previous (v1) wire field count — accepted on decode for rolling-upgrade read-compat. */
    static final int FIELD_COUNT_V1 = 8;

    @Override
    public String encode(ClusterEnvelope envelope) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(envelope.getVersion()).append(SEP);
        sb.append(envelope.getOriginNodeId()).append(SEP);
        sb.append(envelope.getUri()).append(SEP);
        sb.append(envelope.getKind().name()).append(SEP);
        sb.append(envelope.getTargetSessionId() != null ? envelope.getTargetSessionId() : "").append(SEP);
        sb.append(envelope.getTraceparent() != null ? envelope.getTraceparent() : "").append(SEP);
        sb.append(envelope.getTimestamp()).append(SEP);
        // v2: room as the second-to-last field (payload stays the trailing rest-of-line field).
        sb.append(envelope.getRoom() != null ? envelope.getRoom() : "").append(SEP);
        sb.append(Base64.getEncoder().encodeToString(envelope.getPayload()));
        return sb.toString();
    }

    @Override
    public ClusterEnvelope decode(String data) {
        if (data == null || data.isEmpty()) {
            throw new ClusterBrokerException("Empty envelope data");
        }

        // Fast split on pipe — avoid String.split() regex overhead. Split up to FIELD_COUNT-1 pipes;
        // the final field (payload, Base64, no pipes) absorbs the rest of the line. This collects a
        // v2 wire's 9 fields; a v1 wire (8 fields) yields one fewer pipe → fieldIndex stops at 7 and
        // parts[7] holds the v1 payload (the version-aware branch below maps it correctly).
        String[] parts = new String[FIELD_COUNT];
        int fieldIndex = 0;
        int start = 0;
        for (int i = 0; i < data.length() && fieldIndex < FIELD_COUNT - 1; i++) {
            if (data.charAt(i) == SEP) {
                parts[fieldIndex++] = data.substring(start, i);
                start = i + 1;
            }
        }
        // Last collected field — everything after the final pipe (payload for v2; payload for v1 too,
        // since v1 has one fewer field).
        parts[fieldIndex] = data.substring(start);
        int fieldsSeen = fieldIndex + 1;

        // Accept both the v1 (8-field) and v2 (9-field) shapes. Anything shorter is malformed.
        if (fieldsSeen != FIELD_COUNT && fieldsSeen != FIELD_COUNT_V1) {
            throw new ClusterBrokerException("Malformed envelope: expected " + FIELD_COUNT_V1 + " (v1) or "
                    + FIELD_COUNT + " (v2) pipe-separated fields, got " + fieldsSeen);
        }

        try {
            int version = Integer.parseInt(parts[0]);
            // Version gate FIRST (before touching room/payload): a v1 node decoding a v2 wire trips this
            // and discards cleanly; field 0 is always the version token so this is reachable on any shape.
            if (version > ClusterEnvelope.CURRENT_VERSION) {
                log.warn("Received envelope version {} (max supported: {}) — discarding",
                        version, ClusterEnvelope.CURRENT_VERSION);
                return null;
            }

            String originNodeId = parts[1];
            String uri = parts[2];
            ClusterEnvelope.MessageKind kind = ClusterEnvelope.MessageKind.valueOf(parts[3]);
            String targetSessionId = parts[4].isEmpty() ? null : parts[4];
            String traceparent = parts[5].isEmpty() ? null : parts[5];
            long timestamp = Long.parseLong(parts[6]);

            String room;
            byte[] payload;
            if (fieldsSeen == FIELD_COUNT) {
                // v2 wire: room is field 7, payload is field 8.
                room = parts[7].isEmpty() ? null : parts[7];
                payload = Base64.getDecoder().decode(parts[8]);
            } else {
                // v1 wire (8 fields): no room field; payload is field 7.
                room = null;
                payload = Base64.getDecoder().decode(parts[7]);
            }

            return new ClusterEnvelope(version, originNodeId, uri, kind, payload,
                    targetSessionId, traceparent, timestamp, room);
        } catch (IllegalArgumentException e) {
            throw new ClusterBrokerException("Failed to decode envelope: " + e.getMessage(), e);
        }
    }
}
