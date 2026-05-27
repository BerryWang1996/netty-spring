package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.websocket.consts.CloseReason;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WebSocketEventRecorderTest {

    @Test
    void newRecorderReturnsZeroStats() {
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        WebSocketEventStats stats = recorder.getStats();

        assertEquals(0, stats.getHandshakeTotal());
        assertEquals(0, stats.getHandshakeSuccess());
        assertEquals(0, stats.getHandshakeRejectedByInterceptor());
        assertEquals(0, stats.getMessagesReceived());
        assertEquals(0, stats.getMessagesSent());
        assertEquals(0, stats.getTotalCloses());
        for (CloseReason reason : CloseReason.values()) {
            assertEquals(0, stats.getCloseCount(reason));
        }
    }

    @Test
    void recordHandshakeCounters() {
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();

        recorder.recordHandshakeAttempt();
        recorder.recordHandshakeAttempt();
        recorder.recordHandshakeSuccess();
        recorder.recordHandshakeRejectedByInterceptor();

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(2, stats.getHandshakeTotal());
        assertEquals(1, stats.getHandshakeSuccess());
        assertEquals(1, stats.getHandshakeRejectedByInterceptor());
    }

    @Test
    void recordMessageCounters() {
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();

        recorder.recordMessageReceived();
        recorder.recordMessageReceived();
        recorder.recordMessageReceived();
        recorder.recordMessageSent();
        recorder.recordMessageSent();

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(3, stats.getMessagesReceived());
        assertEquals(2, stats.getMessagesSent());
    }

    @Test
    void recordCloseCountersByReason() {
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();

        recorder.recordClose(CloseReason.CLIENT_CLOSE);
        recorder.recordClose(CloseReason.CLIENT_CLOSE);
        recorder.recordClose(CloseReason.HEARTBEAT_TIMEOUT);
        recorder.recordClose(CloseReason.TRANSPORT_ERROR);

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(4, stats.getTotalCloses());
        assertEquals(2, stats.getCloseCount(CloseReason.CLIENT_CLOSE));
        assertEquals(1, stats.getCloseCount(CloseReason.HEARTBEAT_TIMEOUT));
        assertEquals(1, stats.getCloseCount(CloseReason.TRANSPORT_ERROR));
        assertEquals(0, stats.getCloseCount(CloseReason.API_CLOSE));
    }

    @Test
    void closesByReasonMapContainsAllEnumConstants() {
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        recorder.recordClose(CloseReason.CLIENT_CLOSE);
        WebSocketEventStats stats = recorder.getStats();

        Map<CloseReason, Long> closesByReason = stats.getClosesByReason();
        for (CloseReason reason : CloseReason.values()) {
            assertTrue(closesByReason.containsKey(reason),
                    "closesByReason should contain " + reason);
        }
    }

    @Test
    void noopRecorderDoesNotCount() {
        WebSocketEventRecorder noop = WebSocketEventRecorder.noop();

        noop.recordHandshakeAttempt();
        noop.recordHandshakeSuccess();
        noop.recordHandshakeRejectedByInterceptor();
        noop.recordMessageReceived();
        noop.recordMessageSent();
        noop.recordClose(CloseReason.CLIENT_CLOSE);

        WebSocketEventStats stats = noop.getStats();
        assertEquals(0, stats.getHandshakeTotal());
        assertEquals(0, stats.getHandshakeSuccess());
        assertEquals(0, stats.getHandshakeRejectedByInterceptor());
        assertEquals(0, stats.getMessagesReceived());
        assertEquals(0, stats.getMessagesSent());
        assertEquals(0, stats.getTotalCloses());
    }

    @Test
    void recordCloseWithNullReasonIsIgnored() {
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        recorder.recordClose(null);

        WebSocketEventStats stats = recorder.getStats();
        assertEquals(0, stats.getTotalCloses());
    }

    @Test
    void emptyStatsReturnsCorrectDefaults() {
        WebSocketEventStats empty = WebSocketEventStats.empty();
        assertEquals(0, empty.getHandshakeTotal());
        assertEquals(0, empty.getTotalCloses());
        assertEquals(0, empty.getCloseCount(CloseReason.UNKNOWN));
    }

    @Test
    void statsToStringIncludesNonZeroCloseReasons() {
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        recorder.recordHandshakeAttempt();
        recorder.recordHandshakeSuccess();
        recorder.recordClose(CloseReason.CLIENT_CLOSE);
        recorder.recordClose(CloseReason.HEARTBEAT_TIMEOUT);

        String str = recorder.getStats().toString();
        assertTrue(str.contains("handshakes=1/1"));
        assertTrue(str.contains("closes=2"));
        assertTrue(str.contains("client_close=1"));
        assertTrue(str.contains("heartbeat_timeout=1"));
        assertFalse(str.contains("api_close"));
    }

    @Test
    void snapshotIsImmutable() {
        WebSocketEventRecorder recorder = new WebSocketEventRecorder();
        recorder.recordClose(CloseReason.CLIENT_CLOSE);
        WebSocketEventStats snapshot = recorder.getStats();

        recorder.recordClose(CloseReason.CLIENT_CLOSE);
        recorder.recordClose(CloseReason.API_CLOSE);

        assertEquals(1, snapshot.getCloseCount(CloseReason.CLIENT_CLOSE));
        assertEquals(0, snapshot.getCloseCount(CloseReason.API_CLOSE));
        assertEquals(1, snapshot.getTotalCloses());

        WebSocketEventStats freshSnapshot = recorder.getStats();
        assertEquals(2, freshSnapshot.getCloseCount(CloseReason.CLIENT_CLOSE));
        assertEquals(1, freshSnapshot.getCloseCount(CloseReason.API_CLOSE));
        assertEquals(3, freshSnapshot.getTotalCloses());
    }
}
