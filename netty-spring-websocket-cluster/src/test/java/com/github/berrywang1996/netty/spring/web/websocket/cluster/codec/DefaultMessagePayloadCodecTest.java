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
import com.github.berrywang1996.netty.spring.web.websocket.context.AbstractMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.BinaryMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.JsonMessage;
import com.github.berrywang1996.netty.spring.web.websocket.context.TextMessage;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link DefaultMessagePayloadCodec} — the cluster wire format for the framework's
 * built-in message types. The headline cases pin the cross-node JSON fix: a {@link JsonMessage}'s
 * content must travel as real JSON (not {@code Map.toString()}), so the far node delivers exactly
 * what the local node would.
 */
class DefaultMessagePayloadCodecTest {

    private final DefaultMessagePayloadCodec codec = new DefaultMessagePayloadCodec();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void jsonEncodeProducesRealJson_notMapToString() throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "message");
        content.put("text", "hi");

        byte[] enc = codec.encode(new JsonMessage(content));
        String s = new String(enc, StandardCharsets.UTF_8);

        assertTrue(s.startsWith("J:"), "JSON payload must carry the J: prefix");
        String body = s.substring(2);
        // The bug was: body == "{type=message, text=hi}" (Map.toString) — unparseable as JSON.
        assertFalse(body.contains("type=message"), "body must be JSON, not Map.toString(): " + body);
        var node = mapper.readTree(body); // throws if not valid JSON
        assertEquals("message", node.get("type").asText());
        assertEquals("hi", node.get("text").asText());
    }

    @Test
    void jsonRoundTripEqualsLocalDelivery() throws Exception {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "message");
        content.put("nickname", "alice");
        content.put("originNode", "node-a");
        JsonMessage original = new JsonMessage(content);

        AbstractMessage decoded = codec.decode(codec.encode(original));
        assertTrue(decoded instanceof JsonMessage, "J: payload must decode to a JsonMessage");

        // What the originating node would send locally:
        String localJson = original.getObjectMapper().writeValueAsString(content);
        // What the receiving node delivers (via responseMsg):
        TextWebSocketFrame frame = ((JsonMessage) decoded).responseMsg();
        String deliveredJson;
        try {
            deliveredJson = frame.text();
        } finally {
            frame.release();
        }

        assertEquals(mapper.readTree(localJson), mapper.readTree(deliveredJson),
                "cross-node JSON delivery must equal local JSON delivery");
    }

    @Test
    void jsonBroadcastPath_serializeSharedPayload_equalsLocal() throws Exception {
        // The cluster BROADCAST path (ClusterMessageSender -> localSender.topicMessage) delivers via
        // JsonMessage.serializeSharedPayload (zero-copy writeValueAsBytes), NOT responseMsg — so pin
        // THAT path byte-equal to local delivery, since it is the one cross-node broadcasts use.
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("type", "message");
        content.put("text", "hi");
        JsonMessage original = new JsonMessage(content);

        AbstractMessage decoded = codec.decode(codec.encode(original));
        assertTrue(decoded instanceof JsonMessage);

        byte[] localBytes = original.getObjectMapper().writeValueAsBytes(content);
        io.netty.buffer.ByteBuf buf =
                ((JsonMessage) decoded).serializeSharedPayload(io.netty.buffer.ByteBufAllocator.DEFAULT);
        byte[] deliveredBytes;
        try {
            deliveredBytes = new byte[buf.readableBytes()];
            buf.readBytes(deliveredBytes);
        } finally {
            buf.release();
        }
        assertEquals(mapper.readTree(localBytes), mapper.readTree(deliveredBytes),
                "cross-node broadcast (serializeSharedPayload) must equal local delivery");
    }

    @Test
    void textRoundTrip() {
        byte[] enc = codec.encode(new TextMessage("hello"));
        assertEquals("T:hello", new String(enc, StandardCharsets.UTF_8));

        AbstractMessage decoded = codec.decode(enc);
        assertTrue(decoded instanceof TextMessage);
        assertEquals("hello", ((TextMessage) decoded).getContent());
    }

    @Test
    void binaryRoundTrip() {
        byte[] data = {1, 2, 3, (byte) 200};
        byte[] enc = codec.encode(new BinaryMessage(data));
        assertTrue(new String(enc, StandardCharsets.UTF_8).startsWith("B:"));

        AbstractMessage decoded = codec.decode(enc);
        assertTrue(decoded instanceof BinaryMessage);
        assertArrayEquals(data, ((BinaryMessage) decoded).getBinaryData());
    }

    @Test
    void jPrefixWithNonJsonBodyDegradesToText() {
        AbstractMessage decoded = codec.decode("J:not-json".getBytes(StandardCharsets.UTF_8));
        assertTrue(decoded instanceof TextMessage, "a non-JSON J: body should degrade to text, not throw");
    }
}
