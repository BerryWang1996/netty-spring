package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import com.github.berrywang1996.netty.spring.web.context.WebSocketRuntimeStats;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.CloseReason;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class WebSocketRuntimeStatsTest {

    @Test
    void threeDiscoveredMappingsCountTheirSharedRecorderOnce() {
        try (Fixture fixture = new Fixture()) {
            WebSocketEventRecorder recorder = fixture.resolvers.get(0).getEventRecorder();
            fixture.resolvers.forEach(resolver -> assertSame(recorder, resolver.getEventRecorder()));
            recordEvents(recorder, 2, CloseReason.CLIENT_CLOSE);

            WebSocketRuntimeStats stats = fixture.supporter.getWebSocketRuntimeStats();

            assertEquals(3, stats.getMappingCount());
            assertEquals(2L, stats.getEventCounters().get("handshakeTotal"));
            assertEquals(2L, stats.getEventCounters().get("messagesReceived"));
            assertEquals(2L, stats.getEventCounters().get("messagesSent"));
            assertEquals(2L, stats.getEventCounters().get("totalCloses"));
            assertEquals(Map.of("client_close", 2L), stats.getEventCounters().get("closesByReason"));
        }
    }

    @Test
    void independentRecordersAddScalarAndCloseReasonCounters() {
        try (Fixture fixture = new Fixture()) {
            for (int i = 0; i < fixture.resolvers.size(); i++) {
                WebSocketEventRecorder recorder = new WebSocketEventRecorder();
                fixture.resolvers.get(i).setEventRecorder(recorder);
                recordEvents(recorder, i + 1, i == 2 ? CloseReason.API_CLOSE : CloseReason.CLIENT_CLOSE);
            }

            Map<String, Object> counters = fixture.supporter.getWebSocketRuntimeStats().getEventCounters();

            assertEquals(6L, counters.get("handshakeTotal"));
            assertEquals(6L, counters.get("messagesReceived"));
            assertEquals(6L, counters.get("messagesSent"));
            assertEquals(6L, counters.get("totalCloses"));
            assertEquals(Map.of("client_close", 3L, "api_close", 3L), counters.get("closesByReason"));
        }
    }

    @Test
    void nullRecordersContributeZeroWithoutDiscardingTheRemainingRecorder() {
        try (Fixture fixture = new Fixture()) {
            fixture.resolvers.get(0).setEventRecorder(null);
            fixture.resolvers.get(1).setEventRecorder(null);
            recordEvents(fixture.resolvers.get(2).getEventRecorder(), 1, CloseReason.CLIENT_CLOSE);

            Map<String, Object> counters = fixture.supporter.getWebSocketRuntimeStats().getEventCounters();

            assertEquals(1L, counters.get("messagesReceived"));
            assertEquals(1L, counters.get("totalCloses"));
            assertEquals(Map.of("client_close", 1L), counters.get("closesByReason"));
        }
    }

    private static void recordEvents(WebSocketEventRecorder recorder, int count, CloseReason reason) {
        for (int i = 0; i < count; i++) {
            recorder.recordHandshakeAttempt();
            recorder.recordMessageReceived();
            recorder.recordMessageSent();
            recorder.recordClose(reason);
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final GenericApplicationContext context = new GenericApplicationContext();
        private final WebMappingSupporter supporter;
        private final List<MessageMappingResolver> resolvers;

        private Fixture() {
            context.registerBean(ThreeMappings.class);
            context.refresh();
            supporter = new WebMappingSupporter(new NettyServerStartupProperties(), context);
            resolvers = supporter.getWebSocketMappingResolverMap().values().stream()
                    .map(MessageMappingResolver.class::cast).toList();
            assertEquals(3, resolvers.size());
        }

        @Override
        public void close() {
            supporter.shutdown();
            context.close();
        }
    }

    @Component
    public static final class ThreeMappings {
        @MessageMapping({"/ws/one", "/ws/two", "/ws/three"})
        public void onText(String text) {
        }
    }
}
