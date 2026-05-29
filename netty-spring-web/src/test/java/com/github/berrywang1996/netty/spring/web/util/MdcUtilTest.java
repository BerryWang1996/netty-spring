package com.github.berrywang1996.netty.spring.web.util;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import static org.junit.jupiter.api.Assertions.*;

class MdcUtilTest {

    @AfterEach
    void tearDown() {
        MdcUtil.clear();
    }

    @Test
    void setHttpContextSetsRequestIdAndUri() {
        MdcUtil.setHttpContext("req-123", "/api/test", null);

        assertEquals("req-123", MDC.get(MdcUtil.KEY_REQUEST_ID));
        assertEquals("/api/test", MDC.get(MdcUtil.KEY_URI));
        assertNull(MDC.get(MdcUtil.KEY_SESSION_ID));
    }

    @Test
    void setWebSocketContextSetsSessionIdAndUri() {
        MdcUtil.setWebSocketContext("session-abc", "/ws/chat", null);

        assertEquals("session-abc", MDC.get(MdcUtil.KEY_SESSION_ID));
        assertEquals("/ws/chat", MDC.get(MdcUtil.KEY_URI));
        assertNull(MDC.get(MdcUtil.KEY_REQUEST_ID));
    }

    @Test
    void setHttpContextWithEmbeddedChannelSetsRemoteAddr() {
        EmbeddedChannel channel = new EmbeddedChannel();
        try {
            // EmbeddedChannel may have a local address; just verify no NPE
            MdcUtil.setHttpContext("req-456", "/api/data", channel.pipeline().firstContext());

            assertEquals("req-456", MDC.get(MdcUtil.KEY_REQUEST_ID));
            assertEquals("/api/data", MDC.get(MdcUtil.KEY_URI));
            // remoteAddress on EmbeddedChannel is null, so KEY_REMOTE_ADDR should not be set
            assertNull(MDC.get(MdcUtil.KEY_REMOTE_ADDR));
        } finally {
            channel.close();
        }
    }

    @Test
    void clearRemovesAllKeys() {
        MDC.put(MdcUtil.KEY_REQUEST_ID, "req-123");
        MDC.put(MdcUtil.KEY_SESSION_ID, "session-xyz");
        MDC.put(MdcUtil.KEY_URI, "/test");
        MDC.put(MdcUtil.KEY_REMOTE_ADDR, "1.2.3.4");

        MdcUtil.clear();

        assertNull(MDC.get(MdcUtil.KEY_REQUEST_ID));
        assertNull(MDC.get(MdcUtil.KEY_SESSION_ID));
        assertNull(MDC.get(MdcUtil.KEY_URI));
        assertNull(MDC.get(MdcUtil.KEY_REMOTE_ADDR));
    }

    @Test
    void nullValuesAreSkipped() {
        MdcUtil.setHttpContext(null, null, null);

        assertNull(MDC.get(MdcUtil.KEY_REQUEST_ID));
        assertNull(MDC.get(MdcUtil.KEY_URI));
        assertNull(MDC.get(MdcUtil.KEY_REMOTE_ADDR));
    }
}
