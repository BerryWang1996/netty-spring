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

import com.github.berrywang1996.netty.spring.web.websocket.context.MessageSession;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.Test;

import static io.netty.handler.codec.http.HttpMethod.GET;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit tests for {@link HandshakeUserIdResolver} — query/header extraction, null when absent, and
 * malformed source config falling back to the default.
 */
class HandshakeUserIdResolverTest {

    @Test
    void readsUserIdFromDefaultQueryParam() {
        MessageSession session = sessionFor("/ws/chat?userId=alice", null, null);
        try {
            assertEquals("alice", new HandshakeUserIdResolver("query:userId").resolve(session));
            assertEquals("alice", new HandshakeUserIdResolver(null).resolve(session));   // null → default
            assertEquals("alice", new HandshakeUserIdResolver("  ").resolve(session));    // blank → default
        } finally {
            session.release();
        }
    }

    @Test
    void readsUserIdFromCustomQueryParam() {
        MessageSession session = sessionFor("/ws/chat?uid=bob", null, null);
        try {
            assertEquals("bob", new HandshakeUserIdResolver("query:uid").resolve(session));
        } finally {
            session.release();
        }
    }

    @Test
    void readsUserIdFromHeader() {
        MessageSession session = sessionFor("/ws/chat", "X-User-Id", "carol");
        try {
            assertEquals("carol", new HandshakeUserIdResolver("header:X-User-Id").resolve(session));
        } finally {
            session.release();
        }
    }

    @Test
    void nullWhenSourceAbsent() {
        MessageSession session = sessionFor("/ws/chat?other=x", null, null);
        try {
            assertNull(new HandshakeUserIdResolver("query:userId").resolve(session));
            assertNull(new HandshakeUserIdResolver("header:X-User-Id").resolve(session));
        } finally {
            session.release();
        }
    }

    @Test
    void nullWhenValueBlank() {
        // Netty rejects a leading-space header value, so the whitespace-trim path is exercised via the query
        // param (?userId= → empty); the header here is an empty string (allowed) → also resolves to null.
        MessageSession session = sessionFor("/ws/chat?userId=%20%20", "X-User-Id", "");
        try {
            assertNull(new HandshakeUserIdResolver("query:userId").resolve(session));
            assertNull(new HandshakeUserIdResolver("header:X-User-Id").resolve(session));
        } finally {
            session.release();
        }
    }

    @Test
    void malformedSourceConfigFallsBackToDefaultQueryUserId() {
        MessageSession session = sessionFor("/ws/chat?userId=dave", null, null);
        try {
            // No colon, unknown prefix, empty name → all fall back to query:userId.
            assertEquals("dave", new HandshakeUserIdResolver("userId").resolve(session));
            assertEquals("dave", new HandshakeUserIdResolver("cookie:userId").resolve(session));
            assertEquals("dave", new HandshakeUserIdResolver("query:").resolve(session));
        } finally {
            session.release();
        }
    }

    @Test
    void nullSessionResolvesToNull() {
        assertNull(new HandshakeUserIdResolver("query:userId").resolve(null));
    }

    // ---- helpers ----

    private static MessageSession sessionFor(String uri, String headerName, String headerValue) {
        ContextHolder holder = new ContextHolder();
        new EmbeddedChannel(holder);
        FullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, GET, uri);
        if (headerName != null) {
            request.headers().add(headerName, headerValue);
        }
        MessageSession session = new MessageSession("s-1", holder.ctx, request);
        request.release();
        return session;
    }

    private static final class ContextHolder extends ChannelInboundHandlerAdapter {
        private ChannelHandlerContext ctx;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }
    }
}
