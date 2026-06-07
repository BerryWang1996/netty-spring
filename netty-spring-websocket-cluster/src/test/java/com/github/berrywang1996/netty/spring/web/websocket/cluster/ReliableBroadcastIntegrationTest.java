package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.redis.RedisStreamsReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Real-Redis integration for reliable broadcast (Redis Streams). Skipped without localhost:16379. */
class ReliableBroadcastIntegrationTest {

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> conn;
    private static boolean redisAvailable;

    @BeforeAll
    static void check() {
        redisAvailable = ClusterTestRedis.available();
        if (!redisAvailable) {
            return;
        }
        client = ClusterTestRedis.newClient();
        conn = client.connect();
        wipe();
    }

    @AfterAll
    static void cleanup() {
        if (conn != null) { try { wipe(); conn.close(); } catch (Exception ignored) {} }
        if (client != null) { try { client.shutdown(); } catch (Exception ignored) {} }
    }

    private static void wipe() {
        conn.sync().eval("for _,k in ipairs(redis.call('keys','netty:cluster:rstream*')) do redis.call('del',k) end "
                + "redis.call('del','netty:cluster:rstreams')", io.lettuce.core.ScriptOutputType.STATUS);
    }

    private static ClusterEnvelope env(String origin, String uri, String text) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                ("T:" + text).getBytes(), null, null, System.currentTimeMillis());
    }

    @Test
    void publishThenSubscribeDeliversToOtherNode() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        RedisStreamsReliableBroker b = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();
        ClusterSubscription bs = b.reliableSubscribe("/ws/r1", "node-B", bGot::add);
        Thread.sleep(300);
        a.reliablePublish("/ws/r1", env("node-A", "/ws/r1", "m1"));
        long deadline = System.currentTimeMillis() + 5000;
        while (bGot.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(1, bGot.size(), "node B should receive the reliable broadcast");
        assertEquals("node-A", bGot.get(0).getOriginNodeId());
        bs.unsubscribe(); a.shutdown(); b.shutdown();
    }

    @Test
    void offlineNodeCatchesUpOnResubscribe() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        RedisStreamsReliableBroker b = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();

        ClusterSubscription first = b.reliableSubscribe("/ws/r2", "node-B", bGot::add);
        Thread.sleep(300);
        first.unsubscribe();
        Thread.sleep(200);

        for (int i = 0; i < 5; i++) a.reliablePublish("/ws/r2", env("node-A", "/ws/r2", "x" + i));
        Thread.sleep(300);
        assertTrue(bGot.isEmpty(), "nothing delivered while B was offline");

        ClusterSubscription second = b.reliableSubscribe("/ws/r2", "node-B", bGot::add);
        long deadline = System.currentTimeMillis() + 6000;
        while (bGot.size() < 5 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(5, bGot.size(), "B must replay ALL 5 broadcasts missed while offline (at-least-once)");

        second.unsubscribe(); a.shutdown(); b.shutdown();
    }

    /**
     * FIX 4: an oversized inbound stream entry (remote-OOM guard) must be dropped before decode and ACKed
     * (cleared from the PEL), so it is never delivered and never redelivered.
     */
    @Test
    void oversizedInboundEntryIsDroppedAndAcked() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        RedisStreamsReliableBroker b = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        // Cap of 200 chars: comfortably above a small encoded envelope (~45 chars) and below the big one
        // (~700 chars once base64-encoded), so exactly one message is dropped.
        b.setInboundMaxBytes(200);

        List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();
        ClusterSubscription bs = b.reliableSubscribe("/ws/r4", "node-B", bGot::add);
        Thread.sleep(300);

        // A large payload (well over the cap once encoded) and a small in-bounds one.
        StringBuilder big = new StringBuilder("huge-");
        for (int i = 0; i < 50; i++) big.append("0123456789");
        a.reliablePublish("/ws/r4", env("node-A", "/ws/r4", big.toString()));
        a.reliablePublish("/ws/r4", env("node-A", "/ws/r4", "ok"));

        long deadline = System.currentTimeMillis() + 5000;
        while (bGot.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(50);
        Thread.sleep(500); // give the oversized entry a chance to (wrongly) arrive if the guard were missing
        assertEquals(1, bGot.size(), "only the in-bounds message should be delivered; the oversized one is dropped");
        assertTrue(new String(bGot.get(0).getPayload()).contains("ok"));

        // The oversized entry must have been ACKed (dropped from the PEL), not left pending for redelivery.
        io.lettuce.core.models.stream.PendingMessages pend =
                conn.sync().xpending("netty:cluster:rstream:/ws/r4", "g:node-B");
        assertEquals(0L, pend.getCount(), "oversized entry must be ACKed (PEL drained), not left for redelivery");

        bs.unsubscribe(); a.shutdown(); b.shutdown();
    }

    @Test
    void destroyConsumerGroupsForNodeRemovesTrulyStaleGroup() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        // Idle gate effectively disabled so a never-active group (last-delivered 0-0, zero pending) is
        // recognised as stale and destroyed — the genuine cleanup path.
        a.setGroupDestroyIdleMs(1);
        ClusterSubscription s = a.reliableSubscribe("/ws/r3", "dead-node", e -> {});
        Thread.sleep(200);
        assertFalse(conn.sync().xinfoGroups("netty:cluster:rstream:/ws/r3").isEmpty());
        s.unsubscribe();
        a.destroyConsumerGroupsForNode("dead-node");
        assertTrue(conn.sync().xinfoGroups("netty:cluster:rstream:/ws/r3").isEmpty(),
                "a provably-stale (idle, zero-pending) consumer group must be destroyed");
        a.shutdown();
    }

    /**
     * FIX 5 headline: a node that DIES (heartbeat-expires) and RESTARTS with the same id must still replay
     * the backlog it missed. The reconciliation dead-node callback ({@code destroyConsumerGroupsForNode})
     * must NOT wipe a recently-active group's offset+PEL — otherwise the restarted node resubscribes from
     * {@code $} and silently skips the messages published while it was gone.
     */
    @Test
    void deadThenRestartedNodeStillReplaysBacklog_noOffsetWipe() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");
        RedisStreamsReliableBroker a = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        RedisStreamsReliableBroker b = new RedisStreamsReliableBroker(client, new SimpleTextEnvelopeCodec(), 10000, 1000, 64, 1024);
        // Default idle window is 1h; the group is active "now", so it is well within the window and MUST be retained.
        List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();

        // B comes up, consumes one message (group is now active/recent), then "crashes".
        ClusterSubscription first = b.reliableSubscribe("/ws/r5", "node-B", bGot::add);
        Thread.sleep(300);
        a.reliablePublish("/ws/r5", env("node-A", "/ws/r5", "warmup"));
        long warmDeadline = System.currentTimeMillis() + 5000;
        while (bGot.isEmpty() && System.currentTimeMillis() < warmDeadline) Thread.sleep(50);
        assertEquals(1, bGot.size(), "B should receive the warmup message before crashing");
        first.unsubscribe();
        Thread.sleep(200);

        // Reconciliation fires the dead-node callback. The group is recently active → MUST be retained.
        a.destroyConsumerGroupsForNode("node-B");
        assertFalse(conn.sync().xinfoGroups("netty:cluster:rstream:/ws/r5").isEmpty(),
                "a node that may restart must keep its consumer group (offset+PEL) — not destroyed on bare expiry");

        // While B is dead, A broadcasts 5 messages.
        for (int i = 0; i < 5; i++) a.reliablePublish("/ws/r5", env("node-A", "/ws/r5", "x" + i));
        Thread.sleep(300);

        // B restarts with the SAME id and must replay all 5 missed messages (offset preserved → no data loss).
        ClusterSubscription second = b.reliableSubscribe("/ws/r5", "node-B", bGot::add);
        long deadline = System.currentTimeMillis() + 6000;
        while (bGot.size() < 6 && System.currentTimeMillis() < deadline) Thread.sleep(50);
        assertEquals(6, bGot.size(),
                "restarted node-B must replay ALL 5 backlog messages (1 warmup + 5) — group offset was not wiped");

        second.unsubscribe(); a.shutdown(); b.shutdown();
    }

    /**
     * L8: the reliable broker must wire a {@link io.lettuce.core.RedisConnectionStateListener} on its
     * Lettuce client so its {@code state()} flips ACTIVE→DEGRADED the instant the transport drops.
     * Uses a dedicated, locally-managed Testcontainers Redis so we can disrupt the transport without
     * disturbing the shared singleton other tests reuse, and kills the container to drive a real
     * Lettuce {@code onRedisDisconnected} event through the wired listener.
     *
     * <p>Why kill (not pause/unpause): Lettuce does not auto-close on command timeout in default 6.x
     * options, so a paused container only causes command hangs — {@code onRedisDisconnected} never
     * fires until the underlying channel actually closes. A real container kill closes the TCP
     * socket, which Lettuce's transport reliably notices.
     *
     * <p>The companion ACTIVE recovery (DEGRADED→ACTIVE on a connect event) is proven separately in
     * {@link #reliableBroker_listener_onRedisConnected_casesDegradedBackToActive()} via a Mockito
     * spy on the client; Lettuce's EventBus delivery of {@code onRedisConnected} is asynchronous
     * and not race-safe enough for a deterministic integration assertion.
     */
    @Test
    void reliableBroker_state_flipsDegradedOnRealTransportLoss() throws Exception {
        Assumptions.assumeTrue(dockerUsable(), "Docker required for kill+recreate IT");

        // --- PART 1: ACTIVE → DEGRADED on real transport loss ---
        @SuppressWarnings("resource")
        GenericContainer<?> killTarget = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                .withExposedPorts(6379);
        killTarget.start();
        try {
            String killUri = "redis://" + killTarget.getHost() + ":" + killTarget.getMappedPort(6379);
            RedisClient killClient = RedisClient.create(killUri);
            killClient.setDefaultTimeout(java.time.Duration.ofMillis(500));
            try {
                RedisStreamsReliableBroker broker = new RedisStreamsReliableBroker(
                        killClient, new SimpleTextEnvelopeCodec(), 10000, 200, 64, 1024);
                try {
                    assertEquals(BrokerState.ACTIVE, broker.state(),
                            "broker must be ACTIVE immediately after construction");

                    // Kill the redis container → TCP socket closes → Lettuce fires onRedisDisconnected
                    // → broker CASes ACTIVE→DEGRADED through the listener wired in L8.
                    killTarget.getDockerClient().killContainerCmd(killTarget.getContainerId()).exec();

                    // P3: 15s = Docker killContainerCmd latency (~1s) + Lettuce channel-inactive detection
                    // (~1-3s default) + listener-CAS application + Testcontainers slack. Tuned empirically; raise if flakey on slow CI.
                    long degradedDeadline = System.currentTimeMillis() + 15000;
                    while (broker.state() != BrokerState.DEGRADED
                            && System.currentTimeMillis() < degradedDeadline) {
                        Thread.sleep(100);
                    }
                    assertEquals(BrokerState.DEGRADED, broker.state(),
                            "transport loss must flip broker state to DEGRADED via the connection-state listener");
                } finally {
                    broker.shutdown();
                }
            } finally {
                try { killClient.shutdown(); } catch (Exception ignored) {}
            }
        } finally {
            try { killTarget.stop(); } catch (Exception ignored) {}
        }

    }

    /**
     * L8 (unit-level companion to the kill-container IT): proves the CAS path
     * {@code DEGRADED→ACTIVE} via the {@link io.lettuce.core.RedisConnectionStateListener} the broker
     * wires on construction. We spy the {@link RedisClient} so we can capture the listener handed to
     * {@code addListener(...)} and invoke {@code onRedisConnected} directly — independent of
     * Lettuce's event-delivery scheduler (in 6.x the underlying EventBus may dispatch
     * onRedisConnected asynchronously on a background thread, making the live ACTIVE-after-reconnect
     * round-trip flaky in an IT).
     */
    @Test
    void reliableBroker_listener_onRedisConnected_casesDegradedBackToActive() throws Exception {
        Assumptions.assumeTrue(redisAvailable, "Redis not available");

        java.util.concurrent.atomic.AtomicReference<io.lettuce.core.RedisConnectionStateListener> captured =
                new java.util.concurrent.atomic.AtomicReference<>();
        RedisClient spyClient = org.mockito.Mockito.spy(client);
        org.mockito.Mockito.doAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            invocation.callRealMethod();
            return null;
        }).when(spyClient).addListener(org.mockito.ArgumentMatchers.any(io.lettuce.core.RedisConnectionStateListener.class));

        RedisStreamsReliableBroker rb = new RedisStreamsReliableBroker(
                spyClient, new SimpleTextEnvelopeCodec(), 10000, 200, 64, 1024);
        try {
            assertNotNull(captured.get(),
                    "broker must register a RedisConnectionStateListener on the client (L8 wiring)");
            assertEquals(BrokerState.ACTIVE, rb.state(), "broker is ACTIVE on construction");

            // Manually CAS to DEGRADED so the listener's onRedisConnected branch is exercised.
            java.lang.reflect.Field sf = RedisStreamsReliableBroker.class.getDeclaredField("state");
            sf.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.concurrent.atomic.AtomicReference<BrokerState> stateRef =
                    (java.util.concurrent.atomic.AtomicReference<BrokerState>) sf.get(rb);
            stateRef.set(BrokerState.DEGRADED);

            captured.get().onRedisConnected(null, null);

            assertEquals(BrokerState.ACTIVE, rb.state(),
                    "onRedisConnected on the wired listener must CAS DEGRADED→ACTIVE");

            // Disconnect on an already-ACTIVE broker must CAS to DEGRADED (the other half).
            captured.get().onRedisDisconnected(null);
            assertEquals(BrokerState.DEGRADED, rb.state(),
                    "onRedisDisconnected on the wired listener must CAS ACTIVE→DEGRADED");
        } finally {
            rb.shutdown();
        }
    }

    /** True iff Docker is available locally — keeps the pause/unpause test skip-clean in CI without Docker. */
    private static boolean dockerUsable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
