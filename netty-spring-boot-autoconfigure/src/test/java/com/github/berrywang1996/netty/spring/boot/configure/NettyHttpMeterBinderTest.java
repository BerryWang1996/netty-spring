package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.context.ExecutorRuntimeInfo;
import com.github.berrywang1996.netty.spring.web.context.HandlerRuntimeStats;
import com.github.berrywang1996.netty.spring.web.context.HttpRuntimeStats;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NettyHttpMeterBinderTest {

    private SimpleMeterRegistry registry;
    private NettyServerBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        bootstrap = mock(NettyServerBootstrap.class);
        when(bootstrap.getHttpRuntimeStats()).thenReturn(HttpRuntimeStats.empty());
        when(bootstrap.getHandlerRuntimeStats()).thenReturn(HandlerRuntimeStats.empty());
    }

    @Test
    void bindsAllExpectedMetrics() {
        new NettyHttpMeterBinder(bootstrap).bindTo(registry);

        assertNotNull(registry.find("netty.http.response.write.failures").functionCounter());
        assertNotNull(registry.find("netty.http.static.rejected").functionCounter());
        assertNotNull(registry.find("netty.http.static.write.failures").functionCounter());
        assertNotNull(registry.find("netty.http.idle.closes").functionCounter());
        assertNotNull(registry.find("netty.http.websocket.handshake.rejected").functionCounter());
        assertNotNull(registry.find("netty.http.websocket.origin.rejected").functionCounter());

        // Handler pool gauges
        assertNotNull(registry.find("netty.handler.pool.size").gauge());
        assertNotNull(registry.find("netty.handler.pool.active").gauge());
        assertNotNull(registry.find("netty.handler.pool.core").gauge());
        assertNotNull(registry.find("netty.handler.pool.max").gauge());
        assertNotNull(registry.find("netty.handler.queue.size").gauge());
        assertNotNull(registry.find("netty.handler.queue.remaining").gauge());
        assertNotNull(registry.find("netty.handler.permits.available").gauge());
        assertNotNull(registry.find("netty.handler.permits.limit").gauge());

        // Allocator gauges
        assertNotNull(registry.find("netty.allocator.used.heap").gauge());
        assertNotNull(registry.find("netty.allocator.used.direct").gauge());
    }

    @Test
    void countersReflectBootstrapStats() {
        HttpRuntimeStats stats = new HttpRuntimeStats(5, 3, 2, 10, 1, 4);
        when(bootstrap.getHttpRuntimeStats()).thenReturn(stats);

        new NettyHttpMeterBinder(bootstrap).bindTo(registry);

        assertEquals(5.0,
                registry.find("netty.http.response.write.failures").functionCounter().count(), 0.01);
        assertEquals(3.0,
                registry.find("netty.http.static.rejected").functionCounter().count(), 0.01);
        assertEquals(2.0,
                registry.find("netty.http.static.write.failures").functionCounter().count(), 0.01);
        assertEquals(10.0,
                registry.find("netty.http.idle.closes").functionCounter().count(), 0.01);
        assertEquals(1.0,
                registry.find("netty.http.websocket.handshake.rejected").functionCounter().count(), 0.01);
        assertEquals(4.0,
                registry.find("netty.http.websocket.origin.rejected").functionCounter().count(), 0.01);
    }

    @Test
    void emptyStatsReportsZeros() {
        new NettyHttpMeterBinder(bootstrap).bindTo(registry);

        assertEquals(0.0,
                registry.find("netty.http.response.write.failures").functionCounter().count(), 0.01);
        assertEquals(0.0,
                registry.find("netty.http.static.rejected").functionCounter().count(), 0.01);
        assertEquals(0.0,
                registry.find("netty.http.idle.closes").functionCounter().count(), 0.01);
    }

    @Test
    void handlerPoolGaugesReflectBootstrapValues() {
        ExecutorRuntimeInfo executorInfo = new ExecutorRuntimeInfo(
                4, 8, 6, 3, 10, 1014, 100L, 90L, false);
        HandlerRuntimeStats handlerStats = new HandlerRuntimeStats(
                executorInfo, 50, 42, 5L, 3L);
        when(bootstrap.getHandlerRuntimeStats()).thenReturn(handlerStats);

        new NettyHttpMeterBinder(bootstrap).bindTo(registry);

        assertEquals(6.0, registry.find("netty.handler.pool.size").gauge().value(), 0.01);
        assertEquals(3.0, registry.find("netty.handler.pool.active").gauge().value(), 0.01);
        assertEquals(4.0, registry.find("netty.handler.pool.core").gauge().value(), 0.01);
        assertEquals(8.0, registry.find("netty.handler.pool.max").gauge().value(), 0.01);
        assertEquals(10.0, registry.find("netty.handler.queue.size").gauge().value(), 0.01);
        assertEquals(1014.0, registry.find("netty.handler.queue.remaining").gauge().value(), 0.01);
        assertEquals(42.0, registry.find("netty.handler.permits.available").gauge().value(), 0.01);
        assertEquals(50.0, registry.find("netty.handler.permits.limit").gauge().value(), 0.01);
    }

    @Test
    void allocatorGaugesReturnNonNegativeValues() {
        new NettyHttpMeterBinder(bootstrap).bindTo(registry);

        Gauge heapGauge = registry.find("netty.allocator.used.heap").gauge();
        Gauge directGauge = registry.find("netty.allocator.used.direct").gauge();
        assertNotNull(heapGauge);
        assertNotNull(directGauge);
        // Allocator values come from PooledByteBufAllocator.DEFAULT — just verify non-negative
        assertTrue(heapGauge.value() >= 0.0);
        assertTrue(directGauge.value() >= 0.0);
    }
}
