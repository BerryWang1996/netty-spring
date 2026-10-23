package com.github.berrywang1996.netty.spring.web.context;

import com.github.berrywang1996.netty.spring.web.handler.ServiceHandler;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HandlerShutdownTest {
    @Test
    void rejectedTaskRemainsOwnedByItsCaller() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        executor.shutdown();
        WebMappingSupporter supporter = new WebMappingSupporter(new NettyServerStartupProperties(),
                null, Map.of(), executor, new Semaphore(1));
        AtomicInteger discards = new AtomicInteger();
        assertThrows(java.util.concurrent.RejectedExecutionException.class,
                () -> supporter.submitHandle(task(() -> fail("rejected task ran"), discards::incrementAndGet)));
        assertEquals(0, discards.get(), "caller must still own rejected task resources");
        assertEquals(1, supporter.getSemaphore().availablePermits());
    }

    @Test
    void failingDiscardStillCleansRemainingQueuedTasksAndReturnsPermits() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        WebMappingSupporter supporter = new WebMappingSupporter(new NettyServerStartupProperties(),
                null, Map.of(), executor, new Semaphore(3));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger discards = new AtomicInteger();
        try {
            supporter.submitHandle(() -> {
                started.countDown();
                try { finish.await(); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            supporter.submitHandle(task(() -> fail("discarded task ran"), () -> {
                discards.incrementAndGet();
                throw new IllegalStateException("cleanup failed");
            }));
            supporter.submitHandle(task(() -> fail("discarded task ran"), discards::incrementAndGet));
            Thread.currentThread().interrupt();
            supporter.shutdown();
            assertTrue(Thread.interrupted(), "shutdown restores interrupt even when discard fails");
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(2, discards.get());
            assertEquals(3, supporter.getSemaphore().availablePermits());
        } finally {
            Thread.interrupted();
            finish.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void forcedShutdownDoesNotDiscardOrReleaseARunningTaskEarly() throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        WebMappingSupporter supporter = new WebMappingSupporter(new NettyServerStartupProperties(),
                null, Map.of(), executor, new Semaphore(2));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicInteger runningDiscards = new AtomicInteger();
        AtomicInteger queuedDiscards = new AtomicInteger();
        try {
            supporter.submitHandle(task(() -> {
                started.countDown();
                boolean finished = false;
                while (!finished) {
                    try { finish.await(); finished = true; }
                    catch (InterruptedException ignored) { /* simulate an uninterruptible user handler */ }
                }
            }, runningDiscards::incrementAndGet));
            assertTrue(started.await(2, TimeUnit.SECONDS));
            supporter.submitHandle(task(() -> fail("discarded task ran"), queuedDiscards::incrementAndGet));
            Thread.currentThread().interrupt();
            supporter.shutdown();
            assertTrue(Thread.interrupted());
            assertEquals(0, runningDiscards.get());
            assertEquals(1, queuedDiscards.get());
            assertEquals(1, supporter.getSemaphore().availablePermits(), "running work retains its permit");
            finish.countDown();
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals(2, supporter.getSemaphore().availablePermits());
        } finally {
            Thread.interrupted();
            finish.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    private static HandlerSubmitter.DiscardableTask task(Runnable run, Runnable discard) {
        return new HandlerSubmitter.DiscardableTask() {
            @Override public void run() { run.run(); }
            @Override public void discard() { discard.run(); }
        };
    }

    @ParameterizedTest(name = "forced shutdown, interrupted={0}")
    @ValueSource(booleans = {false, true})
    void forcedShutdownReleasesQueuedHttpAndWebSocketMessages(boolean interruptShutdown) throws Exception {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>());
        WebMappingSupporter supporter = new WebMappingSupporter(new NettyServerStartupProperties(),
                null, Map.of(), executor, new Semaphore(3));
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicBoolean interruptRestored = new AtomicBoolean();
        EmbeddedChannel channel = new EmbeddedChannel(new ServiceHandler(supporter));
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1,
                HttpMethod.GET, "/queued");
        TextWebSocketFrame frame = new TextWebSocketFrame("queued");
        Thread shutdown = new Thread(() -> {
            if (interruptShutdown) Thread.currentThread().interrupt();
            supporter.shutdown();
            interruptRestored.set(Thread.currentThread().isInterrupted());
        }, "test-handler-shutdown");
        try {
            supporter.submitHandle(() -> {
                started.countDown();
                try { finish.await(); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            });
            assertTrue(started.await(2, TimeUnit.SECONDS));
            channel.writeInbound(request);
            channel.writeInbound(frame);
            assertEquals(2, executor.getQueue().size());
            assertEquals(0, supporter.getSemaphore().availablePermits());
            assertEquals(1, request.refCnt());
            assertEquals(1, frame.refCnt());

            shutdown.start();
            shutdown.join(8_000);
            assertFalse(shutdown.isAlive(), "bounded shutdown must finish");
            assertTrue(executor.awaitTermination(2, TimeUnit.SECONDS));
            channel.runPendingTasks();
            assertAll(
                    () -> assertEquals(0, request.refCnt(), "queued HTTP retained reference"),
                    () -> assertEquals(0, frame.refCnt(), "queued WebSocket retained reference"),
                    () -> assertEquals(3, supporter.getSemaphore().availablePermits(), "all permits restored"),
                    () -> assertTrue(executor.getQueue().isEmpty()),
                    () -> assertFalse(channel.isOpen(), "discarded request must not leave its client waiting"),
                    () -> assertEquals(interruptShutdown, interruptRestored.get()));
            supporter.shutdown();
            assertEquals(3, supporter.getSemaphore().availablePermits(), "repeated shutdown must not double-release");
        } finally {
            finish.countDown();
            executor.shutdownNow();
            executor.awaitTermination(2, TimeUnit.SECONDS);
            shutdown.join(2_000);
            channel.finishAndReleaseAll();
            // Also clean up the intentionally failing pre-fix reproduction.
            if (request.refCnt() > 0) request.release(request.refCnt());
            if (frame.refCnt() > 0) frame.release(frame.refCnt());
        }
    }
}
