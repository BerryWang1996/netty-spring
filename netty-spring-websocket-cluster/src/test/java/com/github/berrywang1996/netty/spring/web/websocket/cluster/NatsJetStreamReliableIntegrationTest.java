package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.HmacMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsJetStreamReliableBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import io.nats.client.Connection;
import io.nats.client.JetStreamApiException;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Real-NATS integration for the JetStream reliable broker (RC13). Uses the shared
 * {@link ClusterTestNatsJetStream} resolver (Testcontainers {@code nats:2.10 -js}).
 * Skipped (not failed) when no JetStream-enabled NATS is reachable.
 */
class NatsJetStreamReliableIntegrationTest {

    private static Connection sharedConn;
    private static boolean natsAvailable;

    @BeforeAll
    static void check() throws Exception {
        natsAvailable = ClusterTestNatsJetStream.available();
        if (!natsAvailable) {
            return;
        }
        sharedConn = ClusterTestNatsJetStream.newConnection();
    }

    @AfterAll
    static void cleanup() throws Exception {
        if (sharedConn != null) {
            // Best-effort wipe of all reliable streams created by these tests so an in-IDE re-run
            // never trips the ensureStream mismatch guard or leaks state across runs.
            try {
                for (String s : sharedConn.jetStreamManagement().getStreamNames()) {
                    if (s.startsWith("netty-cluster-reliable-")) {
                        try { sharedConn.jetStreamManagement().deleteStream(s); } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
            sharedConn.close();
        }
    }

    private static ClusterEnvelope env(String origin, String uri, String text) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                ("T:" + text).getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
    }

    private static NatsJetStreamReliableBroker newBroker(Connection c, String nodeId) {
        return new NatsJetStreamReliableBroker(c, new SimpleTextEnvelopeCodec(),
                new NoOpMessageAuthenticator(), nodeId,
                10000L, 500L, 32, 1024);
    }

    /** (a) Single publish → received on a separately-instantiated broker on the same NATS. */
    @Test
    void a_publishThenSubscribeDeliversToOtherNode() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "JetStream NATS not available");
        Connection cA = ClusterTestNatsJetStream.newConnection();
        Connection cB = ClusterTestNatsJetStream.newConnection();
        NatsJetStreamReliableBroker a = newBroker(cA, "node-A");
        NatsJetStreamReliableBroker b = newBroker(cB, "node-B");
        try {
            List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();
            ClusterSubscription bs = b.reliableSubscribe("/ws/rc13a", "node-B", bGot::add);
            // Give the durable consumer a moment to bind and start fetching.
            Thread.sleep(500);
            a.reliablePublish("/ws/rc13a", env("node-A", "/ws/rc13a", "m1"));
            long deadline = System.currentTimeMillis() + 10000;
            while (bGot.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, bGot.size(), "node B should receive the reliable broadcast");
            assertEquals("node-A", bGot.get(0).getOriginNodeId());
            bs.unsubscribe();
        } finally {
            a.shutdown(); b.shutdown();
            cA.close(); cB.close();
        }
    }

    /** (b) HEADLINE: replay-on-resync. Node B subscribes, A publishes 3, B unsubscribes (durable
     *  cursor preserved), A publishes 2 more while B is down, B re-subscribes with same nodeId →
     *  must replay all 5. */
    @Test
    void b_replayOnResync_durableCursorPreservedAcrossSubscriptionClose() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "JetStream NATS not available");
        Connection cA = ClusterTestNatsJetStream.newConnection();
        Connection cB = ClusterTestNatsJetStream.newConnection();
        NatsJetStreamReliableBroker a = newBroker(cA, "node-A");
        NatsJetStreamReliableBroker b = newBroker(cB, "node-B-resync");
        try {
            List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();

            // B comes up, A publishes 3, B receives all 3.
            ClusterSubscription first = b.reliableSubscribe("/ws/rc13b", "node-B-resync", bGot::add);
            Thread.sleep(500);
            for (int i = 0; i < 3; i++) {
                a.reliablePublish("/ws/rc13b", env("node-A", "/ws/rc13b", "pre-" + i));
            }
            long warmDeadline = System.currentTimeMillis() + 10000;
            while (bGot.size() < 3 && System.currentTimeMillis() < warmDeadline) {
                Thread.sleep(50);
            }
            assertEquals(3, bGot.size(), "B should receive the first 3 broadcasts");
            first.unsubscribe();
            Thread.sleep(300);

            // B is down. A publishes 2 more.
            for (int i = 0; i < 2; i++) {
                a.reliablePublish("/ws/rc13b", env("node-A", "/ws/rc13b", "during-" + i));
            }
            Thread.sleep(300);

            // B re-subscribes with the SAME nodeId — durable cursor must drive backlog replay.
            ClusterSubscription second = b.reliableSubscribe("/ws/rc13b", "node-B-resync", bGot::add);
            long deadline = System.currentTimeMillis() + 10000;
            while (bGot.size() < 5 && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(5, bGot.size(),
                    "restarted node-B must replay ALL 5 messages (3 pre + 2 missed) — durable cursor preserved");
            second.unsubscribe();
        } finally {
            a.shutdown(); b.shutdown();
            cA.close(); cB.close();
        }
    }

    /** (c) Dead-node consumer cleanup with idle gate: recent-active → retained; small idle window
     *  + sleep → reaped. Mirrors the Redis IT pattern from {@link ReliableBroadcastIntegrationTest}.
     *
     *  <p>We deliberately publish + consume one message before testing retention so the durable's
     *  {@code delivered.lastActive} is a fresh ZonedDateTime (not null / never-active); otherwise
     *  the broker's "lastActive==null → fully idle" branch would reap even under the 1h gate. This
     *  matches the {@code RedisStreamsReliableBroker} contract: an active group with a recent
     *  last-delivery is retained; a never-active or fully-idle group is eligible to reap. */
    @Test
    void c_destroyConsumerGroupsForNode_idleGateGoverns() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "JetStream NATS not available");
        Connection cP = ClusterTestNatsJetStream.newConnection();
        Connection cR = ClusterTestNatsJetStream.newConnection();
        NatsJetStreamReliableBroker publisher = newBroker(cP, "node-Publisher");
        NatsJetStreamReliableBroker reaper = newBroker(cR, "node-Reaper");
        try {
            // r1 subscribes; publisher sends one message; r1 acks → durable's lastActive is fresh.
            List<ClusterEnvelope> r1Got = new CopyOnWriteArrayList<>();
            ClusterSubscription r1Sub = reaper.reliableSubscribe("/ws/rc13c", "r1", r1Got::add);
            Thread.sleep(500);
            publisher.reliablePublish("/ws/rc13c", env("node-Publisher", "/ws/rc13c", "warm"));
            long deadline = System.currentTimeMillis() + 5000;
            while (r1Got.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, r1Got.size(), "r1 should receive the warm-up message");
            r1Sub.unsubscribe();
            Thread.sleep(300);

            // Default idle window (1h): consumer was active <1s ago → must be retained.
            reaper.destroyConsumerGroupsForNode("r1");
            JetStreamApiException retentionCheck = null;
            try {
                cR.jetStreamManagement().getConsumerInfo("netty-cluster-reliable-"
                        + base64Url("/ws/rc13c"), "g_" + base64Url("r1"));
            } catch (JetStreamApiException jae) {
                retentionCheck = jae;
            }
            assertNull(retentionCheck, "recently-active durable must be retained under default 1h idle gate");

            // Now set a tiny idle window (1ms) + sleep past it → must be reaped.
            reaper.setGroupDestroyIdleMs(1L);
            Thread.sleep(500);
            reaper.destroyConsumerGroupsForNode("r1");
            JetStreamApiException reapCheck = null;
            try {
                cR.jetStreamManagement().getConsumerInfo("netty-cluster-reliable-"
                        + base64Url("/ws/rc13c"), "g_" + base64Url("r1"));
            } catch (JetStreamApiException jae) {
                reapCheck = jae;
            }
            assertNotNull(reapCheck,
                    "consumer must be reaped once idle gate is satisfied (idle window exceeded)");
        } finally {
            publisher.shutdown(); reaper.shutdown();
            cP.close(); cR.close();
        }
    }

    /** (d) DEGRADED state on real transport loss (mirrors RC12 L8 IT pattern). Kill the NATS
     *  container → broker state must flip to DEGRADED within 15s; restart → back to ACTIVE within 20s.
     *  Uses a dedicated container so we don't disturb the shared singleton. */
    @Test
    void d_degradedStateOnRealTransportLoss() throws Exception {
        Assumptions.assumeTrue(dockerUsable(), "Docker required for kill+restart IT");

        @SuppressWarnings("resource")
        GenericContainer<?> killTarget = new GenericContainer<>(DockerImageName.parse("nats:2.10"))
                .withExposedPorts(4222)
                .withCommand("-js")
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        killTarget.start();
        try {
            String url = "nats://" + killTarget.getHost() + ":" + killTarget.getMappedPort(4222);
            Connection c = Nats.connect(Options.builder()
                    .server(url)
                    .maxReconnects(-1)
                    .reconnectWait(Duration.ofMillis(100))
                    .build());
            NatsJetStreamReliableBroker broker = newBroker(c, "node-degraded");
            try {
                assertEquals(BrokerState.ACTIVE, broker.state(),
                        "broker must be ACTIVE immediately after construction");

                // Kill the NATS container → socket closes → broker CASes ACTIVE→DEGRADED via ConnectionListener.
                killTarget.getDockerClient().killContainerCmd(killTarget.getContainerId()).exec();

                long degradedDeadline = System.currentTimeMillis() + 15000;
                while (broker.state() != BrokerState.DEGRADED
                        && System.currentTimeMillis() < degradedDeadline) {
                    Thread.sleep(100);
                }
                assertEquals(BrokerState.DEGRADED, broker.state(),
                        "transport loss must flip broker state to DEGRADED via the connection listener");
            } finally {
                try { broker.shutdown(); } catch (Exception ignored) {}
                try { c.close(); } catch (Exception ignored) {}
            }
        } finally {
            try { killTarget.stop(); } catch (Exception ignored) {}
        }
    }

    /** (e) HMAC rejection: a broker with mismatched secret receives nothing across the wire. */
    @Test
    void e_hmacMismatch_dropsAllInbound() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "JetStream NATS not available");
        Connection cA = ClusterTestNatsJetStream.newConnection();
        Connection cB = ClusterTestNatsJetStream.newConnection();
        byte[] secretA = "this-is-a-32+char-cluster-secret!!".getBytes(StandardCharsets.UTF_8);
        byte[] secretB = "DIFFERENT-32-char-cluster-secret!".getBytes(StandardCharsets.UTF_8);
        NatsJetStreamReliableBroker a = new NatsJetStreamReliableBroker(cA, new SimpleTextEnvelopeCodec(),
                new HmacMessageAuthenticator(secretA, true), "node-A-hmac",
                10000L, 500L, 32, 1024);
        NatsJetStreamReliableBroker b = new NatsJetStreamReliableBroker(cB, new SimpleTextEnvelopeCodec(),
                new HmacMessageAuthenticator(secretB, true), "node-B-hmac",
                10000L, 500L, 32, 1024);
        try {
            List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();
            ClusterSubscription bs = b.reliableSubscribe("/ws/rc13e", "node-B-hmac", bGot::add);
            Thread.sleep(500);

            // A signs with secretA, B verifies with secretB → all entries must be dropped at HMAC.
            for (int i = 0; i < 3; i++) {
                a.reliablePublish("/ws/rc13e", env("node-A-hmac", "/ws/rc13e", "hmac-" + i));
            }
            Thread.sleep(2000);
            assertEquals(0, bGot.size(), "HMAC mismatch must drop ALL inbound entries (not delivered)");
            bs.unsubscribe();
        } finally {
            a.shutdown(); b.shutdown();
            cA.close(); cB.close();
        }
    }

    private static String base64Url(String s) {
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean dockerUsable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
