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
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBrokerException;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterMessageListener;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MeshNodeDirectory;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link ClusterBroker} over direct node-to-node Netty TCP (1.10.0-RC4a) — a drop-in for {@code RedisPubSubBroker}
 * (registry/heartbeat stay on Redis, off the message hot path).
 *
 * <p><b>Topology:</b> one TCP <b>server</b> per node (inbound = receive) + a lazily-cached <b>outbound connection per
 * peer</b> (send). Frames are length-prefixed ({@link MeshFrames}) carrying the existing HMAC-wrapped
 * {@link EnvelopeCodec} line. Peer addresses come from {@link MeshNodeDirectory}.
 *
 * <p><b>Scope (RC4a skeleton):</b> {@code unicast} is direct; {@code publish} is <b>naive</b> — sent to all peers
 * (never self), each peer dispatching to its local listeners (a peer with no listener for the URI drops it). Interest-
 * routed broadcast (the fan-out reduction) is RC4b. Backpressure (T4), off-loop dispatch (T5) and the total-isolation
 * degrade trigger (T6) refine this class.
 *
 * @author berrywang1996
 * @since V1.10.0
 */
@Slf4j
public class MeshBroker implements ClusterBroker {

    private final String nodeId;
    private final MeshNodeDirectory directory;
    private final EnvelopeCodec codec;
    private final MessageAuthenticator authenticator;
    private final ClusterRuntimeStats stats;
    private final String bindAddress;
    private final int port;
    private final String advertisedHost;
    private final int maxFrameBytes;
    private final long advertiseTtlMs;
    private final int writeBufferLowWaterMark;
    private final int writeBufferHighWaterMark;

    private final ConcurrentHashMap<String, ClusterMessageListener> broadcastListeners = new ConcurrentHashMap<>();
    private final AtomicReference<ClusterMessageListener> unicastListener = new AtomicReference<>();
    /** Cached outbound channels keyed by peer nodeId. */
    private final ConcurrentHashMap<String, Channel> outbound = new ConcurrentHashMap<>();

    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.RESYNC);
    private volatile TransportStateListener transportStateListener;

    /** Dispatch pool (RC4a M3): the listener callback (local fan-out / app work) runs HERE, never on the Netty I/O
     *  event loop — decode happens on the loop, delivery is handed off. Mirrors the other Redis SPIs' dedicated pools. */
    private final java.util.concurrent.ExecutorService dispatchExecutor;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private Bootstrap clientBootstrap;
    /** Periodic membership tick: re-advertise + proactively connect to peers + evaluate isolation (RC4a M5). */
    private java.util.concurrent.ScheduledExecutorService meshScheduler;

    public MeshBroker(String nodeId, MeshNodeDirectory directory, EnvelopeCodec codec,
                      MessageAuthenticator authenticator, ClusterRuntimeStats stats,
                      String bindAddress, int port, String advertisedHost, int maxFrameBytes, long advertiseTtlMs,
                      int writeBufferLowWaterMark, int writeBufferHighWaterMark) {
        this.nodeId = nodeId;
        this.directory = directory;
        this.codec = codec;
        this.authenticator = authenticator;
        this.stats = stats;
        this.bindAddress = bindAddress;
        this.port = port;
        this.advertisedHost = advertisedHost;
        this.maxFrameBytes = maxFrameBytes;
        this.advertiseTtlMs = advertiseTtlMs;
        this.writeBufferLowWaterMark = writeBufferLowWaterMark;
        this.writeBufferHighWaterMark = writeBufferHighWaterMark;
        this.dispatchExecutor = java.util.concurrent.Executors.newFixedThreadPool(2, namedThreads("cluster-mesh-dispatch"));
    }

    /** Binds the TCP server, prepares the outbound bootstrap, and advertises this node's address. */
    public void start() throws InterruptedException {
        this.bossGroup = new NioEventLoopGroup(1, namedThreads("cluster-mesh-boss"));
        this.workerGroup = new NioEventLoopGroup(0, namedThreads("cluster-mesh-io"));

        ServerBootstrap sb = new ServerBootstrap();
        sb.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(MeshFrames.decoder(maxFrameBytes));
                        ch.pipeline().addLast(new InboundHandler());
                    }
                });
        this.serverChannel = sb.bind(bindAddress, port).sync().channel();

        this.clientBootstrap = new Bootstrap();
        clientBootstrap.group(workerGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.SO_KEEPALIVE, true)
                // RC4a M1 (the verified BLOCKER): bound the outbound buffer so a SLOW peer can't accumulate
                // unflushed writes and OOM the sender. Past the high mark the channel goes !writable and sendTo drops.
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(writeBufferLowWaterMark, writeBufferHighWaterMark))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // Outbound carries length-prefixed frames; inbound on this channel is unused (directional).
                        ch.pipeline().addLast(MeshFrames.prepender());
                    }
                });

        directory.advertise(nodeId, advertisedHost, port, advertiseTtlMs);
        state.set(BrokerState.ACTIVE);

        // Membership tick: re-advertise (keep the TTL alive), proactively connect to peers, and evaluate isolation.
        this.meshScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                namedThreads("cluster-mesh-tick"));
        long tickMs = Math.max(1000L, advertiseTtlMs / 3);
        meshScheduler.scheduleAtFixedRate(this::membershipTick, tickMs, tickMs, TimeUnit.MILLISECONDS);

        log.info("MeshBroker started for node {} — listening {}:{}, advertising {}:{}",
                nodeId, bindAddress, port, advertisedHost, port);
    }

    /** Re-advertise this node, proactively (re)connect to known peers so broadcast doesn't pay connect latency, and
     *  evaluate isolation. Best-effort; never throws out of the scheduler. */
    private void membershipTick() {
        try {
            directory.advertise(nodeId, advertisedHost, port, advertiseTtlMs);
            Map<String, String> peers = directory.peers(nodeId).toCompletableFuture().join();
            int reachable = 0;
            for (Map.Entry<String, String> e : peers.entrySet()) {
                Channel ch = connectionTo(e.getKey(), e.getValue());
                if (ch != null && ch.isActive()) {
                    reachable++;
                }
            }
            evaluateReachability(peers.size(), reachable);
        } catch (Exception e) {
            log.debug("mesh membership tick failed", e);
        }
    }

    /**
     * Isolation rule (RC4a M5): the mesh degrades + fires {@code onTransportLost} ONLY on TOTAL isolation — the node
     * has peers it should reach ({@code shouldReach > 0}) but can reach NONE of them. A single/partial dead peer is
     * per-target (handled by {@link #sendTo}'s failure/drop counting), NOT a global degrade. This keeps the
     * {@code on-redis-loss}/grace/{@code DEGRADED} machinery meaningful in mesh mode ("I am cut off from the whole
     * mesh") instead of silently dead. Package-visible for the regression test.
     */
    void evaluateReachability(int shouldReach, int reachable) {
        if (state.get() == BrokerState.SHUTDOWN) {
            return;
        }
        if (shouldReach > 0 && reachable == 0) {
            if (state.compareAndSet(BrokerState.ACTIVE, BrokerState.DEGRADED)) {
                log.warn("MeshBroker node {} is ISOLATED — 0 of {} peers reachable; degrading", nodeId, shouldReach);
                TransportStateListener l = transportStateListener;
                if (l != null) {
                    l.onTransportLost();
                }
            }
        } else if (reachable > 0) {
            if (state.compareAndSet(BrokerState.DEGRADED, BrokerState.ACTIVE)) {
                log.info("MeshBroker node {} recovered — {} of {} peers reachable", nodeId, reachable, shouldReach);
                TransportStateListener l = transportStateListener;
                if (l != null) {
                    l.onTransportRestored();
                }
            }
        }
    }

    // ---- ClusterBroker ----

    @Override
    public void publish(String uri, ClusterEnvelope envelope) {
        checkActive();
        String wrapped = authenticator.wrap(codec.encode(envelope));
        Map<String, String> peers = directory.peers(nodeId).toCompletableFuture().join();
        for (Map.Entry<String, String> e : peers.entrySet()) {
            sendTo(e.getKey(), e.getValue(), wrapped);
        }
    }

    @Override
    public void unicast(String targetNodeId, ClusterEnvelope envelope) {
        checkActive();
        if (nodeId.equals(targetNodeId)) {
            return; // never send to self
        }
        String wrapped = authenticator.wrap(codec.encode(envelope));
        String addr = directory.peers(nodeId).toCompletableFuture().join().get(targetNodeId);
        if (addr == null) {
            log.debug("unicast: no advertised address for node {} — dropping", targetNodeId);
            stats.incMeshSendFailures();
            return;
        }
        sendTo(targetNodeId, addr, wrapped);
    }

    @Override
    public ClusterSubscription subscribe(String uri, ClusterMessageListener listener) {
        broadcastListeners.put(uri, listener);
        return new MeshSubscription(() -> broadcastListeners.remove(uri, listener));
    }

    @Override
    public ClusterSubscription subscribeUnicast(String nodeId, ClusterMessageListener listener) {
        unicastListener.set(listener);
        return new MeshSubscription(() -> unicastListener.compareAndSet(listener, null));
    }

    @Override
    public BrokerState state() {
        return state.get();
    }

    @Override
    public void setTransportStateListener(TransportStateListener listener) {
        this.transportStateListener = listener;
    }

    @Override
    public void shutdown() {
        state.set(BrokerState.SHUTDOWN);
        if (meshScheduler != null) {
            meshScheduler.shutdownNow();
        }
        try {
            directory.remove(nodeId);
        } catch (Exception e) {
            log.debug("mesh directory remove failed on shutdown", e);
        }
        for (Channel ch : outbound.values()) {
            ch.close();
        }
        outbound.clear();
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully(0, 2, TimeUnit.SECONDS);
        }
        dispatchExecutor.shutdown();
        try {
            if (!dispatchExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                dispatchExecutor.shutdownNow();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            dispatchExecutor.shutdownNow();
        }
        log.info("MeshBroker shut down for node {}", nodeId);
    }

    // ---- send / receive ----

    /** Sends a framed, wrapped envelope to a peer over its cached outbound channel (lazy-connect). */
    protected void sendTo(String peerNodeId, String addr, String wrapped) {
        try {
            Channel ch = connectionTo(peerNodeId, addr);
            if (ch == null || !ch.isActive()) {
                stats.incMeshSendFailures();
                return;
            }
            writeFramed(peerNodeId, ch, wrapped);
        } catch (Exception e) {
            stats.incMeshSendFailures();
            log.debug("mesh send to {} threw", peerNodeId, e);
        }
    }

    /**
     * Writes one framed envelope to a peer channel WITH backpressure (RC4a M1, the verified BLOCKER): if the channel
     * is not writable (its outbound buffer is at the high watermark — a slow peer), the frame is <b>dropped and
     * counted</b> rather than buffered unboundedly (at-most-once already permits a drop; the loss is now visible via
     * {@code mesh.send_dropped_backpressure}, never a silent buffer-until-OOM). Package-visible for the regression test.
     */
    void writeFramed(String peerNodeId, Channel ch, String wrapped) {
        if (!ch.isWritable()) {
            stats.incMeshSendDroppedBackpressure();
            log.debug("mesh peer {} not writable (backpressure) — dropping frame", peerNodeId);
            return;
        }
        ch.writeAndFlush(MeshFrames.toPayload(wrapped)).addListener(f -> {
            if (!f.isSuccess()) {
                stats.incMeshSendFailures();
                log.debug("mesh send to {} failed", peerNodeId, f.cause());
            }
        });
    }

    private Channel connectionTo(String peerNodeId, String addr) {
        Channel existing = outbound.get(peerNodeId);
        if (existing != null && existing.isActive()) {
            return existing;
        }
        int colon = addr.lastIndexOf(':');
        if (colon <= 0) {
            return null;
        }
        String host = addr.substring(0, colon);
        int p = Integer.parseInt(addr.substring(colon + 1));
        ChannelFuture cf = clientBootstrap.connect(host, p);
        Channel ch;
        try {
            ch = cf.sync().channel();
        } catch (Exception e) {
            log.debug("mesh connect to {} ({}) failed", peerNodeId, addr, e);
            return null;
        }
        ch.closeFuture().addListener(f -> outbound.remove(peerNodeId, ch));
        Channel prev = outbound.putIfAbsent(peerNodeId, ch);
        if (prev != null && prev.isActive()) {
            ch.close();
            return prev;
        }
        return ch;
    }

    /** Decodes one inbound frame and dispatches to the registered local listener by kind/URI. */
    protected void onInboundFrame(String wire) {
        stats.incMeshFramesReceived();
        String enc = authenticator.unwrap(wire);
        if (enc == null) {
            log.warn("mesh inbound frame rejected (auth)");
            return;
        }
        ClusterEnvelope env;
        try {
            env = codec.decode(enc);
        } catch (Exception e) {
            log.warn("mesh inbound decode failed", e);
            return;
        }
        if (env == null) {
            return;
        }
        // M3: hand delivery (the listener callback — local fan-out / app work) to the dispatch pool; the Netty I/O
        // event loop must not run listener work (ClusterMessageListener.onMessage must not block the I/O thread).
        try {
            dispatchExecutor.execute(() -> deliver(env));
        } catch (java.util.concurrent.RejectedExecutionException rex) {
            log.debug("mesh dispatch rejected (shutting down) — dropping inbound frame");
        }
    }

    /** Routes a decoded envelope to the right local listener. */
    protected void deliver(ClusterEnvelope env) {
        ClusterEnvelope.MessageKind kind = env.getKind();
        if (kind == ClusterEnvelope.MessageKind.UNICAST || kind == ClusterEnvelope.MessageKind.CLOSE
                || kind == ClusterEnvelope.MessageKind.ROOM_BROADCAST) {
            ClusterMessageListener l = unicastListener.get();
            if (l != null) {
                l.onMessage(env);
            }
            return;
        }
        // BROADCAST / PRESENCE_CHANGE → the broadcast listener for the envelope's URI (PRESENCE_CHANGE rides its
        // reserved channel as the URI). A URI with no local listener is a no-op (RC4a naive broadcast drop).
        ClusterMessageListener l = broadcastListeners.get(env.getUri());
        if (l != null) {
            l.onMessage(env);
        }
    }

    private void checkActive() {
        BrokerState s = state.get();
        if (s == BrokerState.SHUTDOWN) {
            throw new ClusterBrokerException("MeshBroker is shut down");
        }
    }

    private static java.util.concurrent.ThreadFactory namedThreads(String prefix) {
        java.util.concurrent.atomic.AtomicInteger n = new java.util.concurrent.atomic.AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }

    /** Server-side inbound handler: hands each decoded frame string to {@link #onInboundFrame}. */
    private final class InboundHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        protected void channelRead0(io.netty.channel.ChannelHandlerContext ctx, ByteBuf frame) {
            onInboundFrame(MeshFrames.fromPayload(frame));
        }

        @Override
        public void exceptionCaught(io.netty.channel.ChannelHandlerContext ctx, Throwable cause) {
            log.debug("mesh inbound channel error — closing", cause);
            ctx.close();
        }
    }

    private static final class MeshSubscription implements ClusterSubscription {
        private final Runnable onUnsubscribe;
        private volatile boolean active = true;

        MeshSubscription(Runnable onUnsubscribe) {
            this.onUnsubscribe = onUnsubscribe;
        }

        @Override
        public void unsubscribe() {
            if (active) {
                active = false;
                onUnsubscribe.run();
            }
        }

        @Override
        public boolean isActive() {
            return active;
        }
    }
}
