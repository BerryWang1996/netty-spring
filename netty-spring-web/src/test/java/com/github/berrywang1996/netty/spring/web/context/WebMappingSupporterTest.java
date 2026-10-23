package com.github.berrywang1996.netty.spring.web.context;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import io.netty.channel.ChannelHandlerContext;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WebMappingSupporterTest {

    @Test
    void customCounterMapsRemainIndependentEvenWhenTheMapsAreShared() throws Exception {
        Map<String, Object> counters = Map.of("messagesReceived", 2L,
                "closesByReason", Map.of("client_close", 1L));
        Map<String, AbstractMappingResolver> resolvers = Map.of(
                "/one", new CounterResolver(counters, 1),
                "/two", new CounterResolver(counters, 2),
                "/three", new CounterResolver(counters, 3));

        WebSocketRuntimeStats stats = aggregateCounters(resolvers);

        assertEquals(3, stats.getMappingCount());
        assertEquals(6, stats.getActiveSessionCount());
        assertEquals(6L, stats.getEventCounters().get("messagesReceived"));
        assertEquals(Map.of("client_close", 3L), stats.getEventCounters().get("closesByReason"));
    }

    @Test
    void sharedCounterSourcesUseIdentityAndDoNotDeduplicateSessionCounts() throws Exception {
        Object firstSource = new String("equal-but-independent");
        Object secondSource = new String("equal-but-independent");
        Map<String, Object> counters = Map.of("messagesReceived", 2L);
        Map<String, AbstractMappingResolver> resolvers = Map.of(
                "/one", new CounterResolver(new EventCounterSnapshot(firstSource, counters), 1),
                "/two", new CounterResolver(new EventCounterSnapshot(firstSource, counters), 2),
                "/three", new CounterResolver(new EventCounterSnapshot(secondSource, counters), 3));

        WebSocketRuntimeStats stats = aggregateCounters(resolvers);

        assertEquals(3, stats.getMappingCount());
        assertEquals(6, stats.getActiveSessionCount());
        assertEquals(4L, stats.getEventCounters().get("messagesReceived"));
    }

    private static WebSocketRuntimeStats aggregateCounters(Map<String, AbstractMappingResolver> resolvers)
            throws ReflectiveOperationException {
        WebMappingSupporter supporter = new WebMappingSupporter(new NettyServerStartupProperties(),
                null, resolvers, null, null);
        try {
            // Core has no WebSocket module to discover; supply its resolver subset explicitly.
            Field field = WebMappingSupporter.class.getDeclaredField("webSocketMappingResolverMap");
            field.setAccessible(true);
            field.set(supporter, resolvers);
            return supporter.getWebSocketRuntimeStats();
        } finally {
            supporter.shutdown();
        }
    }

    @Test
    void submitHandleRejectsImmediatelyWhenPermitIsUnavailable() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        WebMappingSupporter supporter = new WebMappingSupporter(
                new NettyServerStartupProperties(),
                null,
                Collections.<String, AbstractMappingResolver>emptyMap(),
                executor,
                new Semaphore(1));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch secondRan = new CountDownLatch(1);

        try {
            supporter.submitHandle(new Runnable() {
                @Override
                public void run() {
                    firstStarted.countDown();
                    awaitLatch(releaseFirst);
                }
            });

            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            assertEquals(0, supporter.getSemaphore().availablePermits());

            long start = System.nanoTime();
            RejectedExecutionException exception = assertThrows(RejectedExecutionException.class, new org.junit.jupiter.api.function.Executable() {
                @Override
                public void execute() {
                    supporter.submitHandle(new Runnable() {
                        @Override
                        public void run() {
                            secondRan.countDown();
                        }
                    });
                }
            });
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertTrue(elapsedMillis < 200L);
            assertEquals(1L, secondRan.getCount());
            assertTrue(exception.getMessage().contains("No handler permits available."));
            HandlerRuntimeStats stats = supporter.getRuntimeStats();
            assertEquals(1, stats.getPermitLimit());
            assertEquals(0, stats.getAvailablePermits());
            assertEquals(1L, stats.getPermitRejectedCount());
            assertEquals(0L, stats.getExecutorRejectedCount());
            assertTrue(stats.getExecutor().getActiveCount() >= 1);

            releaseFirst.countDown();
            awaitPermits(supporter.getSemaphore(), 1);

            supporter.submitHandle(new Runnable() {
                @Override
                public void run() {
                    secondRan.countDown();
                }
            });
            assertTrue(secondRan.await(2, TimeUnit.SECONDS));
            awaitPermits(supporter.getSemaphore(), 1);
        } finally {
            releaseFirst.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void submitHandleReleasesPermitWhenExecutorRejectsTask() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        executor.shutdownNow();
        WebMappingSupporter supporter = new WebMappingSupporter(
                new NettyServerStartupProperties(),
                null,
                Collections.<String, AbstractMappingResolver>emptyMap(),
                executor,
                new Semaphore(1));

        assertThrows(RejectedExecutionException.class, new org.junit.jupiter.api.function.Executable() {
            @Override
            public void execute() {
                supporter.submitHandle(new Runnable() {
                    @Override
                    public void run() {
                    }
                });
            }
        });
        assertEquals(1, supporter.getSemaphore().availablePermits());
        HandlerRuntimeStats stats = supporter.getRuntimeStats();
        assertEquals(1L, stats.getExecutorRejectedCount());
        assertEquals(0L, stats.getPermitRejectedCount());
        assertEquals(1, stats.getAvailablePermits());
    }

    @Test
    void usesConfiguredHandlerExecutorAndPermitProperties() throws Exception {
        NettyServerStartupProperties startupProperties = new NettyServerStartupProperties();
        NettyServerStartupProperties.WebSocket webSocket = new NettyServerStartupProperties.WebSocket();
        webSocket.setHandlerCorePoolSize(2);
        webSocket.setHandlerMaxPoolSize(4);
        webSocket.setHandlerKeepAliveTime(9L);
        webSocket.setHandlerQueueCapacity(5);
        webSocket.setHandlerPermitLimit(7);
        startupProperties.setWebSocket(webSocket);

        WebMappingSupporter supporter = new WebMappingSupporter(
                startupProperties,
                null,
                Collections.<String, AbstractMappingResolver>emptyMap(),
                null,
                null);

        try {
            assertEquals(2, supporter.getExecutor().getCorePoolSize());
            assertEquals(4, supporter.getExecutor().getMaximumPoolSize());
            assertEquals(9L, supporter.getExecutor().getKeepAliveTime(TimeUnit.SECONDS));
            assertEquals(5, supporter.getExecutor().getQueue().remainingCapacity());
            assertEquals(7, supporter.getSemaphore().availablePermits());
        } finally {
            supporter.getExecutor().shutdownNow();
            supporter.getExecutor().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void usesConservativeDefaultHandlerExecutorAndPermitProperties() throws Exception {
        int processors = Runtime.getRuntime().availableProcessors();
        int expectedCorePoolSize = Math.max(2, processors);
        int expectedMaxPoolSize = Math.max(expectedCorePoolSize, processors * 2);
        int expectedPermitLimit = expectedMaxPoolSize * 2;
        WebMappingSupporter supporter = new WebMappingSupporter(
                new NettyServerStartupProperties(),
                null,
                Collections.<String, AbstractMappingResolver>emptyMap(),
                null,
                null);

        try {
            assertEquals(expectedCorePoolSize, supporter.getExecutor().getCorePoolSize());
            assertEquals(expectedMaxPoolSize, supporter.getExecutor().getMaximumPoolSize());
            assertEquals(expectedPermitLimit, supporter.getSemaphore().availablePermits());
        } finally {
            supporter.getExecutor().shutdownNow();
            supporter.getExecutor().awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void configuresHttpRuntimeRecorderForResolvers() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        RecordingResolver resolver = new RecordingResolver();
        WebMappingSupporter supporter = new WebMappingSupporter(
                new NettyServerStartupProperties(),
                null,
                Collections.<String, AbstractMappingResolver>singletonMap("/test", resolver),
                executor,
                new Semaphore(1));

        try {
            resolver.recordHttpResponseWriteFailure();

            assertEquals(1L, supporter.getHttpRuntimeStats().getHttpResponseWriteFailureCount());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void shutdownClosesExecutorIdempotently() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<Runnable>());
        WebMappingSupporter supporter = new WebMappingSupporter(
                new NettyServerStartupProperties(),
                null,
                Collections.<String, AbstractMappingResolver>emptyMap(),
                executor,
                new Semaphore(1));

        supporter.shutdown();
        supporter.shutdown();

        assertTrue(executor.isShutdown());
        assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitPermits(Semaphore semaphore, int expectedPermits) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            if (semaphore.availablePermits() == expectedPermits) {
                return;
            }
            Thread.sleep(10L);
        }
        assertEquals(expectedPermits, semaphore.availablePermits());
    }

    private static final class CounterResolver extends AbstractMappingResolver<Object, String> {
        private final Map<String, Object> counters;
        private final int sessions;

        private CounterResolver(Map<String, Object> counters, int sessions) {
            super("/test", Collections.emptyMap(), new Object());
            this.counters = counters;
            this.sessions = sessions;
        }

        @Override
        public Map<String, Object> getEventCounters() {
            return counters;
        }

        @Override
        public int getActiveSessionCount() {
            return sessions;
        }

        @Override
        public void resolve(ChannelHandlerContext ctx, Object msg) {
        }

        @Override
        public void removeSession(String sessionId) {
        }
    }

    private static final class RecordingResolver extends AbstractMappingResolver<Object, String> {

        private RecordingResolver() {
            super("/test", Collections.<String, java.lang.reflect.Method>emptyMap(), new Object());
        }

        private void recordHttpResponseWriteFailure() {
            getHttpRuntimeRecorder().recordHttpResponseWriteFailure();
        }

        @Override
        public void resolve(ChannelHandlerContext ctx, Object msg) {
        }

        @Override
        public void removeSession(String sessionId) {
        }
    }
}
