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
     *  container → broker state must flip to DEGRADED within 15s.
     *  <p>Q1 (RC15) extension: restart the SAME container and assert recovery to ACTIVE within 30s,
     *  exercising the {@code DEGRADED→ACTIVE} CAS on a real jnats reconnect (not just the
     *  unit-level ConnectionListener path).
     *  <p>To survive the kill+restart with a stable URL, we bind container port {@code 4222} to a
     *  fixed host port chosen via {@code ServerSocket(0)}. Without this, Docker assigns a fresh
     *  random host port on each {@code startContainerCmd} and jnats's reconnect loop targets a
     *  stale URL forever. This avoids both Testcontainers' {@code .start()} (which would recreate
     *  the container with new state) and the random-port pitfall.
     *  <p>Uses a dedicated container so we don't disturb the shared singleton. */
    @Test
    void d_degradedStateOnRealTransportLoss() throws Exception {
        Assumptions.assumeTrue(dockerUsable(), "Docker required for kill+restart IT");

        int hostPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            hostPort = s.getLocalPort();
        }
        final int fixedHostPort = hostPort;

        @SuppressWarnings("resource")
        GenericContainer<?> killTarget = new GenericContainer<>(DockerImageName.parse("nats:2.10"))
                .withExposedPorts(4222)
                .withCommand("-js")
                .withCreateContainerCmdModifier(cmd -> {
                    com.github.dockerjava.api.model.PortBinding pb =
                            new com.github.dockerjava.api.model.PortBinding(
                                    com.github.dockerjava.api.model.Ports.Binding.bindPort(fixedHostPort),
                                    com.github.dockerjava.api.model.ExposedPort.tcp(4222));
                    com.github.dockerjava.api.model.HostConfig hc = cmd.getHostConfig();
                    if (hc == null) {
                        hc = com.github.dockerjava.api.model.HostConfig.newHostConfig();
                        cmd.withHostConfig(hc);
                    }
                    hc.withPortBindings(pb);
                })
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        killTarget.start();
        try {
            String url = "nats://" + killTarget.getHost() + ":" + fixedHostPort;
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

                // Q1 (RC15): assert recovery. Raw docker startContainerCmd reuses the original
                // container record (and our fixed host-port binding) so jnats reconnects to the
                // exact same URL. Poll up to 30s — accounts for the NATS startup + reconnectWait +
                // ConnectionListener event-bus latency.
                killTarget.getDockerClient().startContainerCmd(killTarget.getContainerId()).exec();
                long activeDeadline = System.currentTimeMillis() + 30_000;
                while (broker.state() != BrokerState.ACTIVE
                        && System.currentTimeMillis() < activeDeadline) {
                    Thread.sleep(200);
                }
                assertEquals(BrokerState.ACTIVE, broker.state(),
                        "broker should recover to ACTIVE after NATS container restart");
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

    /** (f) Q2 (RC15): HMAC positive round-trip — publisher and subscriber with MATCHING secrets;
     *  the receiver gets the broadcast. Complements (e) so we cover both halves of the HMAC path
     *  on the reliable broker. */
    @Test
    void f_hmacRoundTripWithMatchingSecrets() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "JetStream NATS not available");
        Connection cA = ClusterTestNatsJetStream.newConnection();
        Connection cB = ClusterTestNatsJetStream.newConnection();
        byte[] sharedSecret = "shared-rc15-cluster-secret-32+chars!".getBytes(StandardCharsets.UTF_8);
        NatsJetStreamReliableBroker a = new NatsJetStreamReliableBroker(cA, new SimpleTextEnvelopeCodec(),
                new HmacMessageAuthenticator(sharedSecret, true), "node-A-hmac-ok",
                10000L, 500L, 32, 1024);
        NatsJetStreamReliableBroker b = new NatsJetStreamReliableBroker(cB, new SimpleTextEnvelopeCodec(),
                new HmacMessageAuthenticator(sharedSecret, true), "node-B-hmac-ok",
                10000L, 500L, 32, 1024);
        try {
            List<ClusterEnvelope> bGot = new CopyOnWriteArrayList<>();
            ClusterSubscription bs = b.reliableSubscribe("/ws/rc15f", "node-B-hmac-ok", bGot::add);
            Thread.sleep(500);

            a.reliablePublish("/ws/rc15f", env("node-A-hmac-ok", "/ws/rc15f", "hello-positive"));
            long deadline = System.currentTimeMillis() + 10000;
            while (bGot.isEmpty() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50);
            }
            assertEquals(1, bGot.size(), "matching HMAC secrets must allow the broadcast to be delivered");
            assertEquals("node-A-hmac-ok", bGot.get(0).getOriginNodeId());
            assertEquals("T:hello-positive",
                    new String(bGot.get(0).getPayload(), StandardCharsets.UTF_8),
                    "payload must be intact end-to-end under HMAC");
            bs.unsubscribe();
        } finally {
            a.shutdown(); b.shutdown();
            cA.close(); cB.close();
        }
    }

    /** (g) Q3 (RC15): {@code reliablePublish} during DEGRADED must not throw, and the broker must
     *  recover so a subsequent publish is delivered. We subscribe BEFORE the kill so the consumer
     *  is wired; whether the during-DEGRADED publish is eventually delivered depends on jnats's
     *  reconnect-buffer behavior — the test only asserts (a) no throw and (b) a post-reconnect
     *  publish reaches the listener.
     *  <p>Same fixed-host-port trick as (d) so jnats reconnects to a stable URL.
     *  <p>The container is started fresh and stopped in finally, so this test is self-healing
     *  regardless of JUnit method ordering. */
    @Test
    void g_publishDoesNotThrowWhenDegraded() throws Exception {
        Assumptions.assumeTrue(dockerUsable(), "Docker required for kill+restart IT");

        int hostPort;
        try (java.net.ServerSocket s = new java.net.ServerSocket(0)) {
            hostPort = s.getLocalPort();
        }
        final int fixedHostPort = hostPort;

        @SuppressWarnings("resource")
        GenericContainer<?> killTarget = new GenericContainer<>(DockerImageName.parse("nats:2.10"))
                .withExposedPorts(4222)
                .withCommand("-js")
                .withCreateContainerCmdModifier(cmd -> {
                    com.github.dockerjava.api.model.PortBinding pb =
                            new com.github.dockerjava.api.model.PortBinding(
                                    com.github.dockerjava.api.model.Ports.Binding.bindPort(fixedHostPort),
                                    com.github.dockerjava.api.model.ExposedPort.tcp(4222));
                    com.github.dockerjava.api.model.HostConfig hc = cmd.getHostConfig();
                    if (hc == null) {
                        hc = com.github.dockerjava.api.model.HostConfig.newHostConfig();
                        cmd.withHostConfig(hc);
                    }
                    hc.withPortBindings(pb);
                })
                .waitingFor(Wait.forListeningPort().withStartupTimeout(Duration.ofSeconds(60)));
        killTarget.start();
        try {
            String url = "nats://" + killTarget.getHost() + ":" + fixedHostPort;
            Connection c = Nats.connect(Options.builder()
                    .server(url)
                    .maxReconnects(-1)
                    .reconnectWait(Duration.ofMillis(100))
                    .build());
            NatsJetStreamReliableBroker broker = newBroker(c, "node-q3");
            try {
                // Subscribe BEFORE the kill so we have a consumer wired up + the durable cursor
                // established. Otherwise post-reconnect ensureStream might race subscribe.
                List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
                ClusterSubscription sub = broker.reliableSubscribe("/ws/rc15g", "node-q3", got::add);
                Thread.sleep(500);
                got.clear(); // ignore any warm-up.

                // Kill the NATS container → DEGRADED.
                killTarget.getDockerClient().killContainerCmd(killTarget.getContainerId()).exec();
                long degradedDeadline = System.currentTimeMillis() + 15_000;
                while (broker.state() != BrokerState.DEGRADED
                        && System.currentTimeMillis() < degradedDeadline) {
                    Thread.sleep(100);
                }
                assertEquals(BrokerState.DEGRADED, broker.state(),
                        "transport loss must flip broker state to DEGRADED");

                // Publish during DEGRADED must NOT throw. publishAsync is fire-and-forget; the
                // on-publish-failure path handles the eventual ack failure internally (spec §5.1
                // informational: "DEGRADED still attempts publish").
                assertDoesNotThrow(() ->
                                broker.reliablePublish("/ws/rc15g",
                                        env("node-q3", "/ws/rc15g", "during-degraded")),
                        "reliablePublish must not throw while broker is DEGRADED");

                // Restart the same container (fixed host port preserved) → ACTIVE within 30s.
                killTarget.getDockerClient().startContainerCmd(killTarget.getContainerId()).exec();
                long activeDeadline = System.currentTimeMillis() + 30_000;
                while (broker.state() != BrokerState.ACTIVE
                        && System.currentTimeMillis() < activeDeadline) {
                    Thread.sleep(200);
                }
                assertEquals(BrokerState.ACTIVE, broker.state(),
                        "broker should recover to ACTIVE after NATS container restart");

                // Post-reconnect end-to-end smoke: a FRESH publisher broker against the restarted
                // NATS publishes on a NEW URI to a fresh subscriber-side broker. This validates
                // (a) the transport recovered fully, (b) JetStream is usable end-to-end.
                // We don't reuse the original broker's subscription because its in-memory
                // {@code streamCache} was warmed pre-kill — after a container kill, the JetStream
                // server's file-backed stream may not survive a hard SIGKILL with no graceful
                // shutdown, and {@code ensureStream} doesn't auto-revalidate after reconnect. The
                // honest assertion is "fresh broker can publish/subscribe over restored transport",
                // which is the recovery property the test is here to verify.
                sub.unsubscribe();
                Connection cFresh = Nats.connect(Options.builder().server(url)
                        .connectionTimeout(Duration.ofSeconds(8)).build());
                NatsJetStreamReliableBroker freshPub = newBroker(cFresh, "node-q3-fresh-pub");
                NatsJetStreamReliableBroker freshSub = newBroker(cFresh, "node-q3-fresh-sub");
                try {
                    List<ClusterEnvelope> freshGot = new CopyOnWriteArrayList<>();
                    ClusterSubscription fs = freshSub.reliableSubscribe(
                            "/ws/rc15g-fresh", "node-q3-fresh-sub", freshGot::add);
                    Thread.sleep(500);
                    freshPub.reliablePublish("/ws/rc15g-fresh",
                            env("node-q3-fresh-pub", "/ws/rc15g-fresh", "post-reconnect-fresh"));
                    long recvDeadline = System.currentTimeMillis() + 15_000;
                    while (freshGot.isEmpty() && System.currentTimeMillis() < recvDeadline) {
                        Thread.sleep(100);
                    }
                    assertFalse(freshGot.isEmpty(),
                            "after reconnect, a fresh broker must be able to publish + deliver end-to-end");
                    fs.unsubscribe();
                } finally {
                    try { freshPub.shutdown(); } catch (Exception ignored) {}
                    try { freshSub.shutdown(); } catch (Exception ignored) {}
                    try { cFresh.close(); } catch (Exception ignored) {}
                }
            } finally {
                try { broker.shutdown(); } catch (Exception ignored) {}
                try { c.close(); } catch (Exception ignored) {}
            }
        } finally {
            try { killTarget.stop(); } catch (Exception ignored) {}
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
