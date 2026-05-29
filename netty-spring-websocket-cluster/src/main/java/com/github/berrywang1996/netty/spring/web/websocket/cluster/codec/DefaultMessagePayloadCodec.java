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

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessagePayloadCodec;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Zero-dependency default implementation of {@link MessagePayloadCodec}.
 *
 * <p>Wire format: single-character type prefix + colon + content.
 * <ul>
 *   <li>{@code T:} + UTF-8 text (TextMessage)</li>
 *   <li>{@code J:} + UTF-8 JSON string (JsonMessage — the already-serialized JSON text)</li>
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

    @Override
    public byte[] encode(AbstractMessage message) {
        if (message instanceof TextMessage) {
            return ("T:" + ((TextMessage) message).getContent()).getBytes(StandardCharsets.UTF_8);
        } else if (message instanceof JsonMessage) {
            return ("J:" + ((JsonMessage) message).getContent()).getBytes(StandardCharsets.UTF_8);
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
                case 'J': return new JsonMessage(body);
                case 'B': return new BinaryMessage(Base64.getDecoder().decode(body));
                default:
                    log.debug("Unknown payload type prefix '{}', treating as TextMessage", type);
                    return new TextMessage(s);
            }
        }
        // No recognized prefix — forward-compatible fallback
        return new TextMessage(s);
    }
}
