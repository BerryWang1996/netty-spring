package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.consts.CloseReason;
import com.github.berrywang1996.netty.spring.web.websocket.context.WebSocketEventRecorder;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NettyWebSocketMeterBinderTest {

    private SimpleMeterRegistry registry;
    private WebSocketEventRecorder recorder;
    private NettyServerBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        recorder = new WebSocketEventRecorder();

        MessageMappingResolver resolver = mock(MessageMappingResolver.class);
        when(resolver.getEventRecorder()).thenReturn(recorder);
        when(resolver.getActiveSessionCount()).thenReturn(0);

        Map<String, AbstractMappingResolver> wsMap = new HashMap<>();
        wsMap.put("/ws/test", resolver);

        bootstrap = mock(NettyServerBootstrap.class);
        when(bootstrap.getWebSocketMappingResolverMap()).thenReturn(wsMap);
    }

    @Test
    void bindsAllExpectedMetrics() {
        new NettyWebSocketMeterBinder(bootstrap).bindTo(registry);

        // Handshake counters
        assertNotNull(registry.find("netty.websocket.handshakes.total").functionCounter());
        assertNotNull(registry.find("netty.websocket.handshakes.success").functionCounter());
        assertNotNull(registry.find("netty.websocket.handshakes.rejected").functionCounter());

        // Message counters
        assertNotNull(registry.find("netty.websocket.messages.received").functionCounter());
        assertNotNull(registry.find("netty.websocket.messages.sent").functionCounter());

        // Close counters (one per reason)
        for (CloseReason reason : CloseReason.values()) {
            FunctionCounter counter = registry.find("netty.websocket.sessions.closed")
                    .tag("reason", reason.getTag())
                    .functionCounter();
            assertNotNull(counter, "Missing close counter for reason: " + reason.getTag());
        }

        // Gauges
        assertNotNull(registry.find("netty.websocket.sessions.active").gauge());
        assertNotNull(registry.find("netty.websocket.mappings").gauge());
    }

    @Test
    void handshakeCountersReflectRecorderValues() {
        new NettyWebSocketMeterBinder(bootstrap).bindTo(registry);

        recorder.recordHandshakeAttempt();
        recorder.recordHandshakeAttempt();
        recorder.recordHandshakeSuccess();
        recorder.recordHandshakeRejectedByInterceptor();

        assertEquals(2.0, registry.find("netty.websocket.handshakes.total").functionCounter().count(), 0.01);
        assertEquals(1.0, registry.find("netty.websocket.handshakes.success").functionCounter().count(), 0.01);
        assertEquals(1.0, registry.find("netty.websocket.handshakes.rejected").functionCounter().count(), 0.01);
    }

    @Test
    void messageCountersReflectRecorderValues() {
        new NettyWebSocketMeterBinder(bootstrap).bindTo(registry);

        recorder.recordMessageReceived();
        recorder.recordMessageReceived();
        recorder.recordMessageReceived();
        recorder.recordMessageSent();

        assertEquals(3.0, registry.find("netty.websocket.messages.received").functionCounter().count(), 0.01);
        assertEquals(1.0, registry.find("netty.websocket.messages.sent").functionCounter().count(), 0.01);
    }

    @Test
    void closeCountersTaggedByReason() {
        new NettyWebSocketMeterBinder(bootstrap).bindTo(registry);

        recorder.recordClose(CloseReason.CLIENT_CLOSE);
        recorder.recordClose(CloseReason.CLIENT_CLOSE);
        recorder.recordClose(CloseReason.HEARTBEAT_TIMEOUT);

        FunctionCounter clientClose = registry.find("netty.websocket.sessions.closed")
                .tag("reason", "client_close").functionCounter();
        FunctionCounter heartbeatTimeout = registry.find("netty.websocket.sessions.closed")
                .tag("reason", "heartbeat_timeout").functionCounter();
        FunctionCounter apiClose = registry.find("netty.websocket.sessions.closed")
                .tag("reason", "api_close").functionCounter();

        assertNotNull(clientClose);
        assertNotNull(heartbeatTimeout);
        assertNotNull(apiClose);
        assertEquals(2.0, clientClose.count(), 0.01);
        assertEquals(1.0, heartbeatTimeout.count(), 0.01);
        assertEquals(0.0, apiClose.count(), 0.01);
    }

    @Test
    void activeSessionGaugeReadsFromResolvers() {
        MessageMappingResolver resolver1 = mock(MessageMappingResolver.class);
        MessageMappingResolver resolver2 = mock(MessageMappingResolver.class);
        when(resolver1.getEventRecorder()).thenReturn(recorder);
        when(resolver2.getEventRecorder()).thenReturn(recorder);
        when(resolver1.getActiveSessionCount()).thenReturn(3);
        when(resolver2.getActiveSessionCount()).thenReturn(7);

        Map<String, AbstractMappingResolver> wsMap = new HashMap<>();
        wsMap.put("/ws/a", resolver1);
        wsMap.put("/ws/b", resolver2);

        NettyServerBootstrap customBootstrap = mock(NettyServerBootstrap.class);
        when(customBootstrap.getWebSocketMappingResolverMap()).thenReturn(wsMap);

        new NettyWebSocketMeterBinder(customBootstrap).bindTo(registry);

        Gauge gauge = registry.find("netty.websocket.sessions.active").gauge();
        assertNotNull(gauge);
        assertEquals(10.0, gauge.value(), 0.01);
    }

    @Test
    void mappingCountGaugeReflectsMapSize() {
        new NettyWebSocketMeterBinder(bootstrap).bindTo(registry);

        Gauge gauge = registry.find("netty.websocket.mappings").gauge();
        assertNotNull(gauge);
        assertEquals(1.0, gauge.value(), 0.01);
    }

    @Test
    void emptyResolverMapRegistersNoMetrics() {
        NettyServerBootstrap emptyBootstrap = mock(NettyServerBootstrap.class);
        when(emptyBootstrap.getWebSocketMappingResolverMap()).thenReturn(Collections.emptyMap());

        new NettyWebSocketMeterBinder(emptyBootstrap).bindTo(registry);

        assertEquals(0, registry.getMeters().size());
    }

    @Test
    void nullResolverMapRegistersNoMetrics() {
        NettyServerBootstrap nullBootstrap = mock(NettyServerBootstrap.class);
        when(nullBootstrap.getWebSocketMappingResolverMap()).thenReturn(null);

        new NettyWebSocketMeterBinder(nullBootstrap).bindTo(registry);

        assertEquals(0, registry.getMeters().size());
    }
}
