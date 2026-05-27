package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.context.HttpRuntimeStats;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import io.micrometer.core.instrument.FunctionCounter;
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
}
