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

    private final ConcurrentHashMap<String, ClusterMessageListener> broadcastListeners = new ConcurrentHashMap<>();
    private final AtomicReference<ClusterMessageListener> unicastListener = new AtomicReference<>();
    /** Cached outbound channels keyed by peer nodeId. */
    private final ConcurrentHashMap<String, Channel> outbound = new ConcurrentHashMap<>();

    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.RESYNC);
    private volatile TransportStateListener transportStateListener;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;
    private Bootstrap clientBootstrap;

    public MeshBroker(String nodeId, MeshNodeDirectory directory, EnvelopeCodec codec,
                      MessageAuthenticator authenticator, ClusterRuntimeStats stats,
                      String bindAddress, int port, String advertisedHost, int maxFrameBytes, long advertiseTtlMs) {
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
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // Outbound carries length-prefixed frames; inbound on this channel is unused (directional).
                        ch.pipeline().addLast(MeshFrames.prepender());
                    }
                });

        directory.advertise(nodeId, advertisedHost, port, advertiseTtlMs);
        state.set(BrokerState.ACTIVE);
        log.info("MeshBroker started for node {} — listening {}:{}, advertising {}:{}",
                nodeId, bindAddress, port, advertisedHost, port);
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
            ch.writeAndFlush(MeshFrames.toPayload(wrapped)).addListener(f -> {
                if (!f.isSuccess()) {
                    stats.incMeshSendFailures();
                    log.debug("mesh send to {} failed", peerNodeId, f.cause());
                }
            });
        } catch (Exception e) {
            stats.incMeshSendFailures();
            log.debug("mesh send to {} threw", peerNodeId, e);
        }
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
        deliver(env);
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
