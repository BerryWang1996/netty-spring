package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.context.ExecutorRuntimeInfo;
import com.github.berrywang1996.netty.spring.web.context.HandlerRuntimeStats;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NettyServerHealthIndicatorTest {

    @Test
    void reportsUpWhenServerIsRunning() {
        NettyServerBootstrap bootstrap = mock(NettyServerBootstrap.class);
        when(bootstrap.isRunning()).thenReturn(true);
        when(bootstrap.getPort()).thenReturn(8080);
        ExecutorRuntimeInfo executorInfo = new ExecutorRuntimeInfo(
                4, 8, 6, 2, 5, 1019, 100L, 95L, false);
        HandlerRuntimeStats stats = new HandlerRuntimeStats(executorInfo, 50, 48, 0L, 0L);
        when(bootstrap.getHandlerRuntimeStats()).thenReturn(stats);

        NettyServerHealthIndicator indicator = new NettyServerHealthIndicator(bootstrap);
        Health health = indicator.health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(8080, health.getDetails().get("port"));
        assertEquals(6, health.getDetails().get("handlerPoolSize"));
        assertEquals(2, health.getDetails().get("handlerActiveThreads"));
        assertEquals(5, health.getDetails().get("handlerQueueSize"));
        assertEquals(48, health.getDetails().get("permitsAvailable"));
        assertEquals(50, health.getDetails().get("permitsLimit"));
    }

    @Test
    void reportsDownWhenServerIsStopped() {
        NettyServerBootstrap bootstrap = mock(NettyServerBootstrap.class);
        when(bootstrap.isRunning()).thenReturn(false);

        NettyServerHealthIndicator indicator = new NettyServerHealthIndicator(bootstrap);
        Health health = indicator.health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("Netty server is not running", health.getDetails().get("reason"));
    }
}
