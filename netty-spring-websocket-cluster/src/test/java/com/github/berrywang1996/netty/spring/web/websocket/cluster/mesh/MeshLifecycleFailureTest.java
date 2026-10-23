/*
 * Copyright 2018 berrywang1996
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.berrywang1996.netty.spring.web.websocket.cluster.mesh;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.ClusterRuntimeStats;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.InMemoryMeshNodeDirectory;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.reflect.Field;
import java.net.BindException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.nio.channels.ClosedChannelException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Failure-path ownership checks using real NIO groups/channels, without Redis or black-hole addresses. */
@Timeout(20)
class MeshLifecycleFailureTest {

    @Test
    void bindFailureReleasesCreatedResourcesWithoutRemovingAnotherAdvertisement() throws Exception {
        InMemoryMeshNodeDirectory directory = new InMemoryMeshNodeDirectory();
        directory.advertise("node-A", "existing-host", 12345, 30000).toCompletableFuture().join();
        try (ServerSocket occupied = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))) {
            MeshBroker broker = broker(directory, occupied.getLocalPort());
            try {
                Exception failure = assertThrows(Exception.class, broker::start);
                assertInstanceOf(BindException.class, failure, "the real listening socket must cause the failure");

                EventLoopGroup boss = field(broker, "bossGroup", EventLoopGroup.class);
                EventLoopGroup worker = field(broker, "workerGroup", EventLoopGroup.class);
                ExecutorService dispatch = field(broker, "dispatchExecutor", ExecutorService.class);
                // Check before caller cleanup: a failed @Bean creation has no registered destroy callback.
                assertAll(
                        () -> assertTrue(boss.terminationFuture().await(5, TimeUnit.SECONDS), "failed start must terminate boss group"),
                        () -> assertTrue(worker.terminationFuture().await(5, TimeUnit.SECONDS), "failed start must terminate worker group"),
                        () -> assertTrue(dispatch.isShutdown(), "failed broker must release its dispatch executor"),
                        () -> assertEquals(BrokerState.SHUTDOWN, broker.state()),
                        () -> assertEquals("existing-host:12345", directory.peers("observer")
                                .toCompletableFuture().join().get("node-A"),
                                "a broker that never bound/advertised must not remove an existing directory entry"));
            } finally {
                broker.shutdown();
                awaitGroups(broker);
                directory.shutdown();
            }
        }
    }

    @Test
    void interruptedConnectPreservesInterruptAndClosesUncachedPendingChannel() throws Exception {
        InMemoryMeshNodeDirectory directory = new InMemoryMeshNodeDirectory();
        MeshBroker broker = broker(directory, 0);
        HeldConnect held = new HeldConnect();
        AtomicReference<Channel> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptPreserved = new AtomicBoolean();
        CountDownLatch returned = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try {
                result.set(broker.connectionTo("peer", "127.0.0.1:12345"));
                interruptPreserved.set(Thread.currentThread().isInterrupted());
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                returned.countDown();
            }
        }, "mesh-interrupted-connect-caller");
        caller.setDaemon(true);
        try {
            broker.start();
            // Hold the real Netty connect promise at the outbound boundary: no DNS/network timing race.
            broker.clientBootstrapForTest().handler(held);
            caller.start();
            assertTrue(held.entered.await(5, TimeUnit.SECONDS), "a real NIO channel reached connect");
            assertFalse(held.promise.isDone(), "connect must still be pending before the caller is interrupted");
            caller.interrupt();
            assertTrue(returned.await(5, TimeUnit.SECONDS), "interrupt must release the waiting caller");

            assertAll(
                    () -> assertNull(failure.get()),
                    () -> assertNull(result.get()),
                    () -> assertTrue(interruptPreserved.get(), "connectionTo must restore the caller's interrupt status"),
                    () -> assertTrue(held.promise.isCancelled(), "the abandoned pending connect must be cancelled"),
                    () -> assertTrue(held.channel.closeFuture().await(5, TimeUnit.SECONDS),
                            "the uncached channel must close even though broker shutdown cannot find it"),
                    () -> assertTrue(broker.outboundForTest().isEmpty()));
        } finally {
            caller.interrupt();
            if (held.promise != null) {
                held.promise.tryFailure(new ClosedChannelException());
            }
            if (held.channel != null) {
                held.channel.close().syncUninterruptibly();
            }
            caller.join(5000);
            broker.shutdown();
            awaitGroups(broker);
            directory.shutdown();
        }
    }

    private static MeshBroker broker(InMemoryMeshNodeDirectory directory, int port) {
        return new MeshBroker("node-A", directory, new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator(),
                new ClusterRuntimeStats(), "127.0.0.1", port, "127.0.0.1",
                1_048_576, 30000, 32768, 65536, 5000);
    }

    private static <T> T field(MeshBroker broker, String name, Class<T> type) throws Exception {
        Field field = MeshBroker.class.getDeclaredField(name);
        field.setAccessible(true);
        return type.cast(field.get(broker));
    }

    private static void awaitGroups(MeshBroker broker) throws Exception {
        for (String name : new String[]{"bossGroup", "workerGroup"}) {
            EventLoopGroup group = field(broker, name, EventLoopGroup.class);
            if (group != null) {
                assertTrue(group.terminationFuture().await(5, TimeUnit.SECONDS), "test cleanup must terminate " + name);
            }
        }
    }

    private static final class HeldConnect extends ChannelOutboundHandlerAdapter {
        private final CountDownLatch entered = new CountDownLatch(1);
        private volatile Channel channel;
        private volatile ChannelPromise promise;

        @Override
        public void connect(ChannelHandlerContext ctx, SocketAddress remoteAddress, SocketAddress localAddress,
                            ChannelPromise promise) {
            this.channel = ctx.channel();
            this.promise = promise;
            entered.countDown();
        }
    }
}
