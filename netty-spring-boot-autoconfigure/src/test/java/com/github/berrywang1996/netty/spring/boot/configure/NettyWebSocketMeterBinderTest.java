package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.consts.CloseReason;
import com.github.berrywang1996.netty.spring.web.websocket.context.WebSocketEventRecorder;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

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

        // Per-URI active session gauge
        assertNotNull(registry.find("netty.websocket.sessions.active.uri")
                .tag("uri", "/ws/test").gauge());

        // Distribution metrics
        assertNotNull(registry.find("netty.websocket.message.size").summary());
        assertNotNull(registry.find("netty.websocket.broadcast.fanout").summary());
        assertNotNull(registry.find("netty.websocket.handler.latency").timer());
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

    @Test
    void perUriActiveSessionGaugeReadsFromIndividualResolvers() {
        MessageMappingResolver resolver1 = mock(MessageMappingResolver.class);
        MessageMappingResolver resolver2 = mock(MessageMappingResolver.class);
        when(resolver1.getEventRecorder()).thenReturn(recorder);
        when(resolver2.getEventRecorder()).thenReturn(recorder);
        when(resolver1.getActiveSessionCount()).thenReturn(5);
        when(resolver2.getActiveSessionCount()).thenReturn(3);

        Map<String, AbstractMappingResolver> wsMap = new HashMap<>();
        wsMap.put("/ws/a", resolver1);
        wsMap.put("/ws/b", resolver2);

        NettyServerBootstrap customBootstrap = mock(NettyServerBootstrap.class);
        when(customBootstrap.getWebSocketMappingResolverMap()).thenReturn(wsMap);

        new NettyWebSocketMeterBinder(customBootstrap).bindTo(registry);

        Gauge gaugeA = registry.find("netty.websocket.sessions.active.uri")
                .tag("uri", "/ws/a").gauge();
        Gauge gaugeB = registry.find("netty.websocket.sessions.active.uri")
                .tag("uri", "/ws/b").gauge();
        assertNotNull(gaugeA);
        assertNotNull(gaugeB);
        assertEquals(5.0, gaugeA.value(), 0.01);
        assertEquals(3.0, gaugeB.value(), 0.01);
    }

    @Test
    void distributionMetricsRecordedViaCallback() {
        new NettyWebSocketMeterBinder(bootstrap).bindTo(registry);

        // Record events via the recorder — callback routes them to Micrometer meters
        recorder.recordMessageSize(1024);
        recorder.recordMessageSize(512);
        recorder.recordBroadcastFanout(10);
        recorder.recordHandlerLatency(TimeUnit.MILLISECONDS.toNanos(50));
        recorder.recordCloseWithDuration(CloseReason.CLIENT_CLOSE, TimeUnit.SECONDS.toNanos(30));

        // Verify DistributionSummary values
        DistributionSummary msgSize = registry.find("netty.websocket.message.size").summary();
        assertNotNull(msgSize);
        assertEquals(2, msgSize.count());
        assertEquals(1536.0, msgSize.totalAmount(), 0.01);

        DistributionSummary fanout = registry.find("netty.websocket.broadcast.fanout").summary();
        assertNotNull(fanout);
        assertEquals(1, fanout.count());
        assertEquals(10.0, fanout.totalAmount(), 0.01);

        // Verify Timer values
        Timer latency = registry.find("netty.websocket.handler.latency").timer();
        assertNotNull(latency);
        assertEquals(1, latency.count());

        // Connection duration creates a tagged Timer on demand
        Timer connectionTimer = registry.find("netty.websocket.connection.duration")
                .tag("reason", "client_close").timer();
        assertNotNull(connectionTimer);
        assertEquals(1, connectionTimer.count());
    }

    @Test
    void distributionMetricsRoutedToAllBoundRegistries() {
        // A single binder bound to two distinct registries must route distribution
        // metrics to BOTH, not just the most recently bound one.
        SimpleMeterRegistry registry2 = new SimpleMeterRegistry();
        NettyWebSocketMeterBinder binder = new NettyWebSocketMeterBinder(bootstrap);
        binder.bindTo(registry);
        binder.bindTo(registry2);

        recorder.recordMessageSize(2048);
        recorder.recordHandlerLatency(TimeUnit.MILLISECONDS.toNanos(10));
        recorder.recordCloseWithDuration(CloseReason.CLIENT_CLOSE, TimeUnit.SECONDS.toNanos(5));

        DistributionSummary size1 = registry.find("netty.websocket.message.size").summary();
        DistributionSummary size2 = registry2.find("netty.websocket.message.size").summary();
        assertNotNull(size1);
        assertNotNull(size2);
        assertEquals(1, size1.count());
        assertEquals(1, size2.count());
        assertEquals(2048.0, size1.totalAmount(), 0.01);
        assertEquals(2048.0, size2.totalAmount(), 0.01);

        assertEquals(1, registry.find("netty.websocket.handler.latency").timer().count());
        assertEquals(1, registry2.find("netty.websocket.handler.latency").timer().count());

        assertEquals(1, registry.find("netty.websocket.connection.duration")
                .tag("reason", "client_close").timer().count());
        assertEquals(1, registry2.find("netty.websocket.connection.duration")
                .tag("reason", "client_close").timer().count());
    }

    @Test
    void reBindingSameRegistryDoesNotDoubleCountDistributionMetrics() {
        NettyWebSocketMeterBinder binder = new NettyWebSocketMeterBinder(bootstrap);
        binder.bindTo(registry);
        binder.bindTo(registry); // duplicate bind on the same registry must be ignored

        recorder.recordMessageSize(100);

        DistributionSummary size = registry.find("netty.websocket.message.size").summary();
        assertNotNull(size);
        assertEquals(1, size.count(), "duplicate bindTo on the same registry must not double-count");
    }
}
