package com.github.berrywang1996.netty.spring.web.websocket.consts;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CloseReasonTest {

    @Test
    void allEnumConstantsHaveUniqueTag() {
        Set<String> tags = new HashSet<>();
        for (CloseReason reason : CloseReason.values()) {
            assertNotNull(reason.getTag(), reason.name() + " should have a non-null tag");
            assertFalse(reason.getTag().isEmpty(), reason.name() + " should have a non-empty tag");
            assertTrue(tags.add(reason.getTag()),
                    "Duplicate tag: " + reason.getTag());
        }
    }

    @Test
    void allEnumConstantsHaveDescription() {
        for (CloseReason reason : CloseReason.values()) {
            assertNotNull(reason.getDescription(), reason.name() + " should have a description");
            assertFalse(reason.getDescription().isEmpty(), reason.name() + " should have non-empty description");
        }
    }

    @Test
    void toStringReturnsTag() {
        assertEquals("client_close", CloseReason.CLIENT_CLOSE.toString());
        assertEquals("heartbeat_timeout", CloseReason.HEARTBEAT_TIMEOUT.toString());
        assertEquals("unknown", CloseReason.UNKNOWN.toString());
    }

    @Test
    void enumHasExpectedConstants() {
        assertNotNull(CloseReason.valueOf("CLIENT_CLOSE"));
        assertNotNull(CloseReason.valueOf("API_CLOSE"));
        assertNotNull(CloseReason.valueOf("SERVER_SHUTDOWN"));
        assertNotNull(CloseReason.valueOf("HEARTBEAT_TIMEOUT"));
        assertNotNull(CloseReason.valueOf("TRANSPORT_ERROR"));
        assertNotNull(CloseReason.valueOf("FRAME_TOO_LARGE"));
        assertNotNull(CloseReason.valueOf("DECRYPT_FAILURE"));
        assertNotNull(CloseReason.valueOf("HANDSHAKE_FAILURE"));
        assertNotNull(CloseReason.valueOf("CONNECTED_HANDLER_ERROR"));
        assertNotNull(CloseReason.valueOf("CHANNEL_INACTIVE"));
        assertNotNull(CloseReason.valueOf("CHANNEL_NOT_WRITABLE"));
        assertNotNull(CloseReason.valueOf("WRITE_FAILURE"));
        assertNotNull(CloseReason.valueOf("INTERCEPTOR_REJECTED"));
        assertNotNull(CloseReason.valueOf("UNKNOWN"));
    }
}
