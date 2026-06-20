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

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * {@link ClusterBroker} over direct node-to-node Netty TCP (1.10.0-RC4a) — a drop-in for {@code RedisPubSubBroker}
 * (registry/heartbeat stay on Redis, off the message hot path).
 *
 * <p><b>Topology:</b> one TCP <b>server</b> per node (inbound = receive) + a lazily-cached <b>outbound connection per
 * peer</b> (send). Frames are length-prefixed ({@link MeshFrames}) carrying the existing HMAC-wrapped
 * {@link EnvelopeCodec} line. Peer addresses come from {@link MeshNodeDirectory}.
 *
 * <p><b>Scope:</b> {@code unicast} is direct. {@code publish} is <b>interest-routed (RC4b)</b> when a
 * {@link MeshInterestRouter} is wired (via {@link #setInterestRouter}): it targets only {@code interestedNodes(uri) ∩
 * live-membership}, falling back to all-peers (RC4a behavior) when the router is absent, the URI is a reserved channel,
 * or a registry read fails. Each peer dispatches to its local listeners (a peer with no listener for the URI drops it).
 * RC4a foundations remain: backpressure (M1), off-loop dispatch (M3), and the total-isolation degrade trigger (M5).
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
    /** RC4a MF2: TCP connect timeout (ms) applied to the outbound bootstrap (ChannelOption.CONNECT_TIMEOUT_MILLIS) so a
     *  dead/black-holing peer can't block the publish/unicast caller thread for Netty's 30s default. */
    private final long connectTimeoutMs;

    private final ConcurrentHashMap<String, ClusterMessageListener> broadcastListeners = new ConcurrentHashMap<>();
    private final AtomicReference<ClusterMessageListener> unicastListener = new AtomicReference<>();
    /** Cached outbound channels keyed by peer nodeId. */
    private final ConcurrentHashMap<String, Channel> outbound = new ConcurrentHashMap<>();

    private final AtomicReference<BrokerState> state = new AtomicReference<>(BrokerState.RESYNC);
    private volatile TransportStateListener transportStateListener;

    /**
     * RC4a MF1: heartbeat-liveness view for membership intersection. Supplies the node IDs the cluster heartbeat
     * currently considers DEAD (expired). {@link #membershipTick()} subtracts these from the directory's advertised
     * peers so mesh membership is the spec-mandated {@code live-by-heartbeat ∩ has-address} ({@link MeshNodeDirectory}
     * contract): a crashed peer whose mesh address has not yet TTL-expired never counts toward "peers I should reach",
     * so a healthy sole-survivor can't false-degrade (and, under {@code on-redis-loss=CLOSE_ALL}, force-close every
     * local client). Default (unset) = empty set = trust the directory verbatim (legacy behavior / tests with no
     * heartbeat). Wired by auto-config to {@code () -> heartbeat.findExpiredNodes(timeoutMs)}.
     */
    private volatile Supplier<Set<String>> deadNodeView = Collections::emptySet;

    /** RC4b: send-side interest routing (null = no routing → all-peers, RC4a behavior). */
    private volatile MeshInterestRouter interestRouter;

    /** RC4c BL5: in-memory peer-address snapshot refreshed by the membership tick; the hot path reads this, not Redis. */
    private volatile Map<String, String> peerSnapshot = Collections.emptyMap();
    /** RC4c: bounds the start() snapshot populate + the unicast snapshot-miss fallback. Default 2s. */
    private volatile long commandTimeoutMs = 2000L;
    /** RC4c: per-peer SEND-PATH reconnect backoff: peerNodeId → [notBeforeMs, currentBackoffMs]. The membership tick
     *  dials raw (no backoff) since it is the sole DEGRADED→ACTIVE recovery probe. */
    private final ConcurrentHashMap<String, long[]> reconnect = new ConcurrentHashMap<>();
    private volatile long reconnectBackoffBaseMs = 1000L;
    private volatile long reconnectBackoffMaxMs = 30_000L;
    /** RC4c BL3: outbound-connection idle (no WRITE) reap timeout (ms). 0 = disabled (default; auto-config sets it). */
    private volatile long idleTimeoutMs = 0L;
    /** RC4c test hook: number of actual outbound dial attempts (to distinguish a backoff-skip from a dialed+failed). */
    private final java.util.concurrent.atomic.AtomicInteger dialAttempts = new java.util.concurrent.atomic.AtomicInteger();

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
                      int writeBufferLowWaterMark, int writeBufferHighWaterMark, long connectTimeoutMs) {
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
        this.connectTimeoutMs = connectTimeoutMs;
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
                // RC4a MF2: bound the blocking connect (connectionTo does cf.sync() on the publish/unicast caller
                // thread) so a dead/black-holing peer can't stall the hot path for Netty's 30s default. Honors the
                // documented server.netty.websocket.cluster.mesh.connect-timeout-ms knob.
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS,
                        (int) Math.min(Math.max(connectTimeoutMs, 1L), Integer.MAX_VALUE))
                // RC4a M1 (the verified BLOCKER): bound the outbound buffer so a SLOW peer can't accumulate
                // unflushed writes and OOM the sender. Past the high mark the channel goes !writable and sendTo drops.
                .option(ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new WriteBufferWaterMark(writeBufferLowWaterMark, writeBufferHighWaterMark))
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        // RC4c BL3: WRITER_IDLE reap (write-only channel — READ is never satisfied, so ALL_IDLE would
                        // reap a merely-slow peer; WRITER_IDLE reaps only a genuinely-unused connection).
                        if (idleTimeoutMs > 0) {
                            ch.pipeline().addLast(new io.netty.handler.timeout.IdleStateHandler(
                                    0, idleTimeoutMs, 0, TimeUnit.MILLISECONDS));
                            ch.pipeline().addLast(new IdleCloseHandler());
                        }
                        // Outbound carries length-prefixed frames; inbound on this channel is unused (directional).
                        ch.pipeline().addLast(MeshFrames.prepender());
                    }
                });

        directory.advertise(nodeId, advertisedHost, port, advertiseTtlMs);
        state.set(BrokerState.ACTIVE);

        // RC4c BL5: populate the snapshot once (bounded) so publish/unicast work immediately, not empty until +tick.
        try {
            this.peerSnapshot = directory.peers(nodeId).toCompletableFuture()
                    .get(commandTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.debug("initial mesh peer snapshot failed/timed out — empty until first tick", e);
        }

        // Membership tick: re-advertise (keep the TTL alive), proactively connect to peers, and evaluate isolation.
        this.meshScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(
                namedThreads("cluster-mesh-tick"));
        long tickMs = Math.max(1000L, advertiseTtlMs / 3);
        meshScheduler.scheduleAtFixedRate(this::membershipTick, tickMs, tickMs, TimeUnit.MILLISECONDS);

        log.info("MeshBroker started for node {} — listening {}:{}, advertising {}:{}",
                nodeId, bindAddress, port, advertisedHost, port);
    }

    /** Re-advertise this node, proactively (re)connect to known peers so broadcast doesn't pay connect latency, and
     *  evaluate isolation. Best-effort; never throws out of the scheduler. Package-visible for the regression test. */
    void membershipTick() {
        try {
            directory.advertise(nodeId, advertisedHost, port, advertiseTtlMs);
            Map<String, String> peers = directory.peers(nodeId).toCompletableFuture().join();
            this.peerSnapshot = peers;   // RC4c BL5: refresh the hot-path snapshot from the tick's fresh read
            // RC4a MF1: membership = live-by-heartbeat ∩ has-address. A peer the cluster heartbeat already declared
            // DEAD is not a membership obligation even if its advertised mesh address still lingers until TTL — so it
            // must not count toward shouldReach (else a healthy sole-survivor false-degrades on stale dead-peer
            // addresses). deadNodeView defaults to empty (trust the directory) when no heartbeat is wired.
            Set<String> dead = deadNodeView.get();
            int shouldReach = 0;
            int reachable = 0;
            for (Map.Entry<String, String> e : peers.entrySet()) {
                if (dead != null && dead.contains(e.getKey())) {
                    continue; // heartbeat-dead peer — drop its lingering address from the membership view
                }
                shouldReach++;
                Channel ch = connectionTo(e.getKey(), e.getValue());
                if (ch != null && ch.isActive()) {
                    reachable++;
                }
            }
            evaluateReachability(shouldReach, reachable);
            // RC4c: prune the send-path backoff map to live∩address peers (a dead/vanished peer doesn't leak an entry).
            Set<String> live = new java.util.HashSet<>(peers.keySet());
            live.removeAll(dead == null ? Collections.emptySet() : dead);
            reconnect.keySet().retainAll(live);
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
        } else {
            // NOT isolated — either a peer is reachable (reachable > 0), OR there are no live peers to reach at all
            // (shouldReach == 0: I'm alone, or every advertised peer is heartbeat-dead). Both mean "not cut off from
            // the mesh", so clear any prior mesh-degrade. The shouldReach == 0 case is load-bearing (RC4a MF1): without
            // it a true sole-survivor that briefly degraded on a stale dead-peer address would latch DEGRADED forever,
            // since recovery only ever fires on reachable > 0 — which a lone node never sees.
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
        Map<String, String> peers = this.peerSnapshot;   // RC4c BL5: snapshot, no Redis on the broadcast hot path
        MeshInterestRouter router = this.interestRouter;
        // RC4b: null => no routing / reserved channel / read failure => all-peers (RC4a). A non-null set (possibly
        // empty) is authoritative => skip peers with no live session for this uri.
        Set<String> interested = (router == null) ? null : router.nodesForUriCached(uri);
        for (Map.Entry<String, String> e : peers.entrySet()) {
            if (interested != null && !interested.contains(e.getKey())) {
                continue; // peer has no live audience for this uri
            }
            sendTo(e.getKey(), e.getValue(), wrapped);
        }
    }

    @Override
    public void onNodeLeft(String nodeId) {
        MeshInterestRouter router = this.interestRouter;
        if (router != null) {
            router.onNodeLeft(nodeId);
        }
    }

    @Override
    public void unicast(String targetNodeId, ClusterEnvelope envelope) {
        checkActive();
        if (nodeId.equals(targetNodeId)) {
            return; // never send to self
        }
        String wrapped = authenticator.wrap(codec.encode(envelope));
        String addr = this.peerSnapshot.get(targetNodeId);
        if (addr == null) {
            // RC4c BL5: snapshot miss for a registry-known target — pay ONE bounded direct read AND warm the whole
            // snapshot from it (no per-message SCAN storm on a fresh-target join), preserving unicast's ~0 window.
            try {
                Map<String, String> fresh = directory.peers(nodeId).toCompletableFuture()
                        .get(commandTimeoutMs, TimeUnit.MILLISECONDS);
                this.peerSnapshot = fresh;
                addr = fresh.get(targetNodeId);
            } catch (Exception e) {
                addr = null;
            }
        }
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
            Channel ch = connectionForSend(peerNodeId, addr);
            if (ch == null || !ch.isActive()) {
                return;   // RC4d: the null/inactive reason (backoff skip OR dial failure) is already counted in connectionForSend
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

    /** RC4c: send-path connect — gates only a fresh DIAL on the per-peer backoff window; a cached-active channel
     *  (incl. one the membership tick just reconnected) is returned regardless. Package-visible for the regression
     *  test. The membership tick uses the raw {@link #connectionTo} (no backoff) since it is the sole recovery probe. */
    Channel connectionForSend(String peerNodeId, String addr) {
        Channel existing = outbound.get(peerNodeId);
        if (existing != null && existing.isActive()) {
            reconnect.remove(peerNodeId);   // RC4c: a live channel ⇒ the peer is reachable ⇒ clear any stale backoff
            return existing;
        }
        long[] b = reconnect.get(peerNodeId);
        if (b != null && System.currentTimeMillis() < b[0]) {
            stats.incMeshReconnectBackoffSkips();   // RC4d: a deliberate shed — NOT a send failure
            return null;   // within backoff, no live channel → skip the dial (at-most-once drop)
        }
        Channel ch = connectionTo(peerNodeId, addr);
        if (ch == null || !ch.isActive()) {
            long cur = (b == null) ? reconnectBackoffBaseMs : Math.min(b[1] * 2, reconnectBackoffMaxMs);
            reconnect.put(peerNodeId, new long[]{ System.currentTimeMillis() + cur, cur });
            stats.incMeshSendFailures();   // RC4d: a genuine dial/connect failure, counted once at its origin
        } else {
            reconnect.remove(peerNodeId);
        }
        return ch;
    }

    /** Returns a live outbound channel to a peer, dialing (and caching) one if needed. Package-visible for the
     *  regression test. */
    Channel connectionTo(String peerNodeId, String addr) {
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
        dialAttempts.incrementAndGet();   // RC4c test hook: count actual dial attempts (backoff-skip vs dialed)
        ChannelFuture cf = clientBootstrap.connect(host, p);
        Channel ch;
        try {
            ch = cf.sync().channel();
        } catch (Exception e) {
            log.debug("mesh connect to {} ({}) failed", peerNodeId, addr, e);
            return null;
        }
        ch.closeFuture().addListener(f -> outbound.remove(peerNodeId, ch));
        // RC4a BL1: cache the fresh channel, REPLACING any dead-but-still-mapped entry. A plain putIfAbsent would
        // return a dead prev (its async closeFuture-remove hasn't fired yet) WITHOUT storing ch — orphaning a live
        // connection (fd leak) and re-dialing on every send to a flapping peer. The CAS loop evicts the dead entry and
        // retries, so we either store ch or hand back a genuinely live prev (the legitimate double-connect race).
        for (;;) {
            Channel prev = outbound.putIfAbsent(peerNodeId, ch);
            if (prev == null) {
                return ch; // cached fresh
            }
            if (prev.isActive()) {
                ch.close(); // another thread already cached a live channel — drop ours
                return prev;
            }
            // prev is dead but still mapped — evict it, then loop to retry the insert (so ch actually gets cached).
            outbound.remove(peerNodeId, prev);
        }
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

    /**
     * RC4a MF1: sets the heartbeat-liveness view used to intersect the directory's advertised peers down to
     * {@code live-by-heartbeat ∩ has-address} (see {@link #deadNodeView}). Wired by auto-config to the cluster
     * heartbeat's expired-node set. Null = keep the default (empty = trust the directory verbatim).
     */
    public void setDeadNodeView(Supplier<Set<String>> deadNodeView) {
        if (deadNodeView != null) {
            this.deadNodeView = deadNodeView;
        }
    }

    /** RC4b: sets the interest router so {@link #publish} targets only peers hosting a live session for the URI.
     *  Null keeps RC4a all-peers. */
    public void setInterestRouter(MeshInterestRouter interestRouter) {
        this.interestRouter = interestRouter;
    }

    /** RC4c: bounds the initial snapshot populate + the unicast fallback read (auto-config sets the command timeout). */
    public void setCommandTimeoutMs(long commandTimeoutMs) {
        if (commandTimeoutMs > 0) {
            this.commandTimeoutMs = commandTimeoutMs;
        }
    }

    /** RC4c: per-peer send-path reconnect backoff window (base doubles per consecutive failed dial, up to max). */
    public void setReconnectBackoff(long baseMs, long maxMs) {
        if (baseMs > 0) {
            this.reconnectBackoffBaseMs = baseMs;
        }
        if (maxMs >= this.reconnectBackoffBaseMs) {
            this.reconnectBackoffMaxMs = maxMs;
        }
    }

    /** RC4c BL3: outbound idle-reap timeout (ms); 0 = disabled. MUST be set before {@link #start()} (builds the pipeline). */
    public void setIdleTimeoutMs(long idleTimeoutMs) {
        if (idleTimeoutMs >= 0) {
            this.idleTimeoutMs = idleTimeoutMs;
        }
    }

    /** Test hook: the node-address directory (package-visible). */
    MeshNodeDirectory directoryForTest() {
        return directory;
    }

    /** Test hook: the outbound channel cache (package-visible) — for the dead-entry-replacement regression test. */
    Map<String, Channel> outboundForTest() {
        return outbound;
    }

    /** Test hook: count of actual outbound dial attempts (RC4c backoff regression test). */
    int dialAttemptsForTest() {
        return dialAttempts.get();
    }

    /** Test hook: the outbound client bootstrap (package-visible) — to assert ChannelOptions (e.g. connect timeout). */
    Bootstrap clientBootstrapForTest() {
        return clientBootstrap;
    }

    // ---- RC4d observability accessors ----

    /** RC4d: the broker's own runtime stats — where mesh transport counters are written; the meter binder reads THIS. */
    public ClusterRuntimeStats runtimeStats() {
        return stats;
    }

    /** RC4d gauge: live outbound channels currently cached (lazily dialed; usually &lt;= knownPeerCount). */
    public int activeOutboundConnections() {
        return outbound.size();
    }

    /** RC4d gauge: peers in this node's directory snapshot (raw advertised view by address TTL; may briefly include
     *  a heartbeat-dead peer whose mesh address has not yet expired). */
    public int knownPeerCount() {
        return peerSnapshot.size();
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

    /** RC4c BL3: closes an outbound channel on a WRITER_IDLE event — its closeFuture listener evicts it from the
     *  cache; the next send re-dials lazily (honoring backoff). */
    private static final class IdleCloseHandler extends io.netty.channel.ChannelInboundHandlerAdapter {
        @Override
        public void userEventTriggered(io.netty.channel.ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof io.netty.handler.timeout.IdleStateEvent) {
                ctx.close();
            } else {
                ctx.fireUserEventTriggered(evt);
            }
        }
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
