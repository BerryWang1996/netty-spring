package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.context.ExecutorRuntimeInfo;
import com.github.berrywang1996.netty.spring.web.context.HandlerRuntimeStats;
import com.github.berrywang1996.netty.spring.web.context.HttpRuntimeStats;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufAllocatorMetric;
import io.netty.buffer.ByteBufAllocatorMetricProvider;
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
        // Both built-in allocators expose memory usage.
        assertTrue(heapGauge.value() >= 0.0);
        assertTrue(directGauge.value() >= 0.0);
    }

    @Test
    void allocatorGaugesAggregateDistinctInstancesAndRemainLive() {
        ByteBufAllocator defaultAllocator = allocator(128, 256);
        ByteBufAllocator sharedAllocator = allocator(512, 1024);
        new NettyHttpMeterBinder(bootstrap, defaultAllocator, sharedAllocator).bindTo(registry);

        Gauge heap = registry.get("netty.allocator.used.heap").gauge();
        Gauge direct = registry.get("netty.allocator.used.direct").gauge();
        assertEquals(640.0, heap.value());
        assertEquals(1280.0, direct.value());
        assertEquals("bytes", heap.getId().getBaseUnit());
        assertEquals("bytes", direct.getId().getBaseUnit());

        when(metric(defaultAllocator).usedHeapMemory()).thenReturn(256L);
        when(metric(sharedAllocator).usedDirectMemory()).thenReturn(2048L);
        assertEquals(768.0, heap.value());
        assertEquals(2304.0, direct.value());
    }

    @Test
    void allocatorGaugesCountSharedDefaultInstanceOnce() {
        ByteBufAllocator sharedAllocator = allocator(128, 256);
        new NettyHttpMeterBinder(bootstrap, sharedAllocator, sharedAllocator).bindTo(registry);

        assertEquals(128.0, registry.get("netty.allocator.used.heap").gauge().value());
        assertEquals(256.0, registry.get("netty.allocator.used.direct").gauge().value());
        verify(metric(sharedAllocator)).usedHeapMemory();
        verify(metric(sharedAllocator)).usedDirectMemory();
    }

    @Test
    void allocatorGaugesReportUnknownPerMemoryTypeWithoutPartialTotals() {
        ByteBufAllocator defaultAllocator = allocator(-1, 256);
        ByteBufAllocator sharedAllocator = allocator(512, 1024);
        new NettyHttpMeterBinder(bootstrap, defaultAllocator, sharedAllocator).bindTo(registry);

        Gauge heap = registry.get("netty.allocator.used.heap").gauge();
        Gauge direct = registry.get("netty.allocator.used.direct").gauge();
        assertTrue(Double.isNaN(heap.value()));
        assertEquals(1280.0, direct.value());

        when(metric(defaultAllocator).usedHeapMemory()).thenReturn(128L);
        when(metric(sharedAllocator).usedDirectMemory()).thenReturn(-1L);
        assertEquals(640.0, heap.value());
        assertTrue(Double.isNaN(direct.value()));
    }

    @Test
    void allocatorWithoutMetricsMakesTotalsUnknown() {
        ByteBufAllocator defaultAllocator = mock(ByteBufAllocator.class);
        ByteBufAllocator sharedAllocator = allocator(512, 1024);
        new NettyHttpMeterBinder(bootstrap, defaultAllocator, sharedAllocator).bindTo(registry);

        assertTrue(Double.isNaN(registry.get("netty.allocator.used.heap").gauge().value()));
        assertTrue(Double.isNaN(registry.get("netty.allocator.used.direct").gauge().value()));
    }

    private static ByteBufAllocator allocator(long heap, long direct) {
        ByteBufAllocator allocator = mock(ByteBufAllocator.class,
                withSettings().extraInterfaces(ByteBufAllocatorMetricProvider.class));
        ByteBufAllocatorMetric metric = mock(ByteBufAllocatorMetric.class);
        when(((ByteBufAllocatorMetricProvider) allocator).metric()).thenReturn(metric);
        when(metric.usedHeapMemory()).thenReturn(heap);
        when(metric.usedDirectMemory()).thenReturn(direct);
        return allocator;
    }

    private static ByteBufAllocatorMetric metric(ByteBufAllocator allocator) {
        return ((ByteBufAllocatorMetricProvider) allocator).metric();
    }
}
