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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessagePayloadCodec;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Default implementation of {@link MessagePayloadCodec} (the {@code J:} JSON path delegates to the message's
 * own Jackson mapper; no extra dependency beyond what {@code netty-spring-websocket} already pulls in).
 *
 * <p>Wire format: single-character type prefix + colon + content.
 * <ul>
 *   <li>{@code T:} + UTF-8 text (TextMessage)</li>
 *   <li>{@code J:} + UTF-8 JSON text (JsonMessage — its content object serialized to JSON via the
 *       message's own {@link com.fasterxml.jackson.databind.ObjectMapper})</li>
 *   <li>{@code B:} + Base64-encoded binary (BinaryMessage)</li>
 * </ul>
 *
 * <p>This codec handles the framework's built-in message types only. Users with custom
 * {@link AbstractMessage} subclasses should provide their own {@link MessagePayloadCodec}.
 *
 * @author berrywang1996
 * @since V1.8.0
 */
@Slf4j
public class DefaultMessagePayloadCodec implements MessagePayloadCodec {

    /** Shared mapper used to re-parse JSON payloads on decode (read-only; thread-safe). */
    private static final ObjectMapper JSON_MAPPER = new ObjectMapper();

    @Override
    public byte[] encode(AbstractMessage message) {
        if (message instanceof TextMessage) {
            return ("T:" + ((TextMessage) message).getContent()).getBytes(StandardCharsets.UTF_8);
        } else if (message instanceof JsonMessage) {
            // Serialize the content OBJECT to JSON text using the message's own ObjectMapper —
            // the same serialization the local sender performs in JsonMessage.responseMsg(). Using
            // string concatenation here would invoke the content's toString() (e.g. a Map renders as
            // "{a=b}", NOT JSON), so the receiving node would deliver an unparseable body. (See the
            // cross-node demo smoke: a JSON broadcast must arrive as valid JSON on the far node.)
            JsonMessage jsonMessage = (JsonMessage) message;
            ObjectMapper mapper = jsonMessage.getObjectMapper();
            try {
                String json = mapper.writeValueAsString(jsonMessage.getContent());
                return ("J:" + json).getBytes(StandardCharsets.UTF_8);
            } catch (Exception e) {
                // Should not happen for the demo/typical payloads; fall back so cross-node delivery
                // degrades to a (still-delivered) text frame rather than dropping the message.
                log.warn("Failed to serialize JsonMessage content to JSON for cluster transport — "
                        + "falling back to toString()", e);
                return ("J:" + jsonMessage.getContent()).getBytes(StandardCharsets.UTF_8);
            }
        } else if (message instanceof BinaryMessage) {
            byte[] raw = ((BinaryMessage) message).getBinaryData();
            return ("B:" + Base64.getEncoder().encodeToString(raw)).getBytes(StandardCharsets.UTF_8);
        } else {
            log.warn("Unknown message type {} — encoding via toString()", message.getClass().getName());
            return ("T:" + message.toString()).getBytes(StandardCharsets.UTF_8);
        }
    }

    @Override
    public AbstractMessage decode(byte[] payload) {
        String s = new String(payload, StandardCharsets.UTF_8);
        if (s.length() >= 2 && s.charAt(1) == ':') {
            char type = s.charAt(0);
            String body = s.substring(2);
            switch (type) {
                case 'T': return new TextMessage(body);
                case 'J':
                    // The body is JSON TEXT (produced by encode()). Parse it back into a JSON tree so
                    // the receiving node's JsonMessage.responseMsg() (writeValueAsString) reproduces the
                    // SAME JSON. Wrapping the raw String instead would double-encode it into a JSON
                    // string literal ("{...}") on the wire to the browser.
                    try {
                        return new JsonMessage(JSON_MAPPER.readTree(body));
                    } catch (Exception notJson) {
                        // Not valid JSON (e.g. a legacy/foreign payload): preserve it verbatim as a
                        // text frame rather than failing the whole delivery.
                        log.debug("Payload prefixed 'J:' but body is not valid JSON — treating as TextMessage");
                        return new TextMessage(body);
                    }
                case 'B':
                    // Guard against a user TextMessage that legitimately starts with "B:" but
                    // whose body is not valid Base64 — don't let it throw out of the decode path.
                    try {
                        return new BinaryMessage(Base64.getDecoder().decode(body));
                    } catch (IllegalArgumentException notBase64) {
                        log.debug("Payload prefixed 'B:' but body is not valid Base64 — treating as TextMessage");
                        return new TextMessage(s);
                    }
                default:
                    log.debug("Unknown payload type prefix '{}', treating as TextMessage", type);
                    return new TextMessage(s);
            }
        }
        // No recognized prefix — forward-compatible fallback
        return new TextMessage(s);
    }
}
