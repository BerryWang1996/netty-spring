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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBrokerException;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterMessageListener;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterSubscription;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.JetStreamSubscription;
import io.nats.client.Message;
import io.nats.client.PullSubscribeOptions;
import io.nats.client.api.AckPolicy;
import io.nats.client.api.ConsumerConfiguration;
import io.nats.client.api.ConsumerInfo;
import io.nats.client.api.DeliverPolicy;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.Error;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.SequenceInfo;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import io.nats.client.impl.NatsJetStreamMetaData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/** Unit tests for {@link NatsJetStreamReliableBroker} — Mockito mocks of jnats JetStream views. */
class NatsJetStreamReliableBrokerTest {

    private Connection conn;
    private JetStream js;
    private JetStreamManagement jsm;
    private EnvelopeCodec codec;

    @BeforeEach
    void setUp() throws Exception {
        conn = mock(Connection.class);
        js = mock(JetStream.class);
        jsm = mock(JetStreamManagement.class);
        codec = new SimpleTextEnvelopeCodec();
        when(conn.jetStream()).thenReturn(js);
        when(conn.jetStreamManagement()).thenReturn(jsm);
    }

    private NatsJetStreamReliableBroker newBroker() {
        return newBroker("node-A");
    }

    private NatsJetStreamReliableBroker newBroker(String nodeId) {
        return new NatsJetStreamReliableBroker(conn, codec, new NoOpMessageAuthenticator(),
                nodeId, 10000L, 200L, 16, 1024);
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static ClusterEnvelope envelope(String origin, String uri, String text) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                ("T:" + text).getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
    }

    /** Builds a real {@link StreamConfiguration} via the public builder. */
    private static StreamConfiguration streamCfg(StorageType st, DiscardPolicy dp,
                                                 RetentionPolicy rp, long maxMsgs, int replicas,
                                                 String name) {
        return StreamConfiguration.builder()
                .name(name)
                .subjects("netty.reliable." + name.substring("netty-cluster-reliable-".length()))
                .storageType(st)
                .discardPolicy(dp)
                .retentionPolicy(rp)
                .maxMessages(maxMsgs)
                .replicas(replicas)
                .build();
    }

    private static StreamInfo streamInfoWith(StreamConfiguration cfg) {
        StreamInfo info = mock(StreamInfo.class);
        when(info.getConfiguration()).thenReturn(cfg);
        return info;
    }

    /** Build a real JetStreamApiException carrying the given apiErrorCode (used to simulate
     *  "stream not found" / "consumer not found"). Uses reflection to reach the package-private
     *  {@code Error(int, int, String)} ctor — Mockito-mocking Error inside a chained {@code when()}
     *  trips Mockito's UnfinishedStubbing guard. */
    private static JetStreamApiException notFoundException(int apiErrorCode) {
        try {
            java.lang.reflect.Constructor<Error> ctor =
                    Error.class.getDeclaredConstructor(int.class, int.class, String.class);
            ctor.setAccessible(true);
            Error err = ctor.newInstance(404, apiErrorCode, "not found");
            return new JetStreamApiException(err);
        } catch (Exception e) {
            throw new AssertionError("Could not synthesize Error via reflection", e);
        }
    }

    // ===== T1 — skeleton + lifecycle =====

    @Test
    void t1_constructs_initialStateActive_shutdownFlipsToShutdown() {
        NatsJetStreamReliableBroker b = newBroker();
        assertEquals(BrokerState.ACTIVE, b.state());
        b.shutdown();
        assertEquals(BrokerState.SHUTDOWN, b.state());
    }

    @Test
    void t1_registersConnectionListenerOnConstruct() {
        newBroker();
        verify(conn, atLeastOnce()).addConnectionListener(any(ConnectionListener.class));
    }

    // ===== T2 — ensureStream + mismatch detection =====

    @Test
    void t2_ensureStream_idempotent_skipsAddWhenExistingConfigMatches() throws Exception {
        String b64uri = b64("/ws/x");
        String streamName = "netty-cluster-reliable-" + b64uri;
        StreamInfo si = streamInfoWith(streamCfg(StorageType.File, DiscardPolicy.Old,
                RetentionPolicy.Limits, 10000L, 1, streamName));
        when(jsm.getStreamInfo(streamName)).thenReturn(si);
        when(js.publishAsync(anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        NatsJetStreamReliableBroker b = newBroker();
        b.reliablePublish("/ws/x", envelope("node-A", "/ws/x", "m1"));
        b.reliablePublish("/ws/x", envelope("node-A", "/ws/x", "m2"));
        b.shutdown();

        // getStreamInfo called exactly once (cached after success); addStream never called.
        verify(jsm, times(1)).getStreamInfo(streamName);
        verify(jsm, never()).addStream(any());
    }

    @Test
    void t2_ensureStream_createsStreamOnNotFound() throws Exception {
        String b64uri = b64("/ws/new");
        String streamName = "netty-cluster-reliable-" + b64uri;
        StreamInfo created = streamInfoWith(streamCfg(StorageType.File, DiscardPolicy.Old,
                RetentionPolicy.Limits, 10000L, 1, streamName));
        when(jsm.getStreamInfo(streamName)).thenThrow(notFoundException(10059));
        when(jsm.addStream(any(StreamConfiguration.class))).thenReturn(created);
        when(js.publishAsync(anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        NatsJetStreamReliableBroker b = newBroker();
        b.reliablePublish("/ws/new", envelope("node-A", "/ws/new", "m1"));
        b.shutdown();

        ArgumentCaptor<StreamConfiguration> cap = ArgumentCaptor.forClass(StreamConfiguration.class);
        verify(jsm).addStream(cap.capture());
        StreamConfiguration sc = cap.getValue();
        assertEquals(streamName, sc.getName());
        assertEquals(StorageType.File, sc.getStorageType());
        assertEquals(DiscardPolicy.Old, sc.getDiscardPolicy());
        assertEquals(RetentionPolicy.Limits, sc.getRetentionPolicy());
        assertEquals(10000L, sc.getMaxMsgs());
        assertEquals(1, sc.getReplicas());
        assertTrue(sc.getSubjects().contains("netty.reliable." + b64uri));
    }

    @Test
    void ensureStream_rejectsExcessivelyLongStreamName() throws Exception {
        // Q5 (RC14): stream-name length guard — pre-check fires BEFORE any jsm call. NATS rejects
        // stream names > 255 chars; the broker must throw a clear ClusterBrokerException up front
        // rather than letting the less-friendly jnats diagnostic surface on jsm.getStreamInfo.
        // streamName = "netty-cluster-reliable-" (23 chars) + b64uri; we need streamName.length() > 255,
        // so b64uri.length() must be > 232. Base64url ratio of raw URI bytes is ~4/3 (no padding),
        // so a 200-char ascii URI produces a 268-char b64uri → 291-char streamName.
        StringBuilder uri = new StringBuilder("/ws/");
        for (int i = 0; i < 200; i++) uri.append('a');
        String longUri = uri.toString();

        NatsJetStreamReliableBroker b = newBroker();
        ClusterBrokerException ex = assertThrows(ClusterBrokerException.class,
                () -> b.reliablePublish(longUri, envelope("node-A", longUri, "hello")));
        assertTrue(ex.getMessage().contains("Stream name too long"),
                "expected clear diagnostic, got: " + ex.getMessage());
        // Guard must short-circuit BEFORE any jnats round-trip.
        verify(jsm, never()).getStreamInfo(anyString());
        verify(jsm, never()).addStream(any());
        b.shutdown();
    }

    @Test
    void t2_ensureStream_throwsClusterBrokerExceptionOnConfigMismatch() throws Exception {
        String b64uri = b64("/ws/x");
        String streamName = "netty-cluster-reliable-" + b64uri;
        // Different storage type → mismatch.
        StreamInfo mismatched = streamInfoWith(streamCfg(StorageType.Memory, DiscardPolicy.Old,
                RetentionPolicy.Limits, 10000L, 1, streamName));
        when(jsm.getStreamInfo(streamName)).thenReturn(mismatched);

        NatsJetStreamReliableBroker b = newBroker();
        // ensureStream is called inside reliablePublish — the CBE must surface.
        assertThrows(ClusterBrokerException.class, () -> b.ensureStream(b64uri));
        verify(jsm, never()).addStream(any());
        b.shutdown();
    }

    // ===== T3 — reliablePublish =====

    @Test
    void t3_reliablePublish_wrapsWithAuthenticator_publishesToSubject() throws Exception {
        String b64uri = b64("/ws/chat");
        String streamName = "netty-cluster-reliable-" + b64uri;
        StreamInfo si = streamInfoWith(streamCfg(StorageType.File, DiscardPolicy.Old,
                RetentionPolicy.Limits, 10000L, 1, streamName));
        when(jsm.getStreamInfo(streamName)).thenReturn(si);
        when(js.publishAsync(anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        MessageAuthenticator auth = mock(MessageAuthenticator.class);
        when(auth.wrap(anyString())).thenAnswer(inv -> "WRAP|" + inv.getArgument(0));
        NatsJetStreamReliableBroker b = new NatsJetStreamReliableBroker(conn, codec, auth, "node-A",
                10000L, 200L, 16, 1024);

        b.reliablePublish("/ws/chat", envelope("node-A", "/ws/chat", "hello"));

        verify(auth).wrap(anyString());
        ArgumentCaptor<String> sub = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<byte[]> body = ArgumentCaptor.forClass(byte[].class);
        verify(js).publishAsync(sub.capture(), body.capture());
        assertEquals("netty.reliable." + b64uri, sub.getValue());
        String published = new String(body.getValue(), StandardCharsets.UTF_8);
        assertTrue(published.startsWith("WRAP|"), "expected HMAC wrap prefix on the published body");
        b.shutdown();
    }

    @Test
    void t3_reliablePublish_throwsAfterShutdown() {
        NatsJetStreamReliableBroker b = newBroker();
        b.shutdown();
        assertThrows(ClusterBrokerException.class,
                () -> b.reliablePublish("/ws/x", envelope("node-A", "/ws/x", "m1")));
    }

    // ===== T4 — reliableSubscribe + consume path =====

    @Test
    void t4_subscribe_createsDurableConsumer_andStartsFetchLoop() throws Exception {
        String b64uri = b64("/ws/sub");
        String streamName = "netty-cluster-reliable-" + b64uri;
        String subject = "netty.reliable." + b64uri;
        String consumerName = "g_" + b64("node-B");

        StreamInfo si = streamInfoWith(streamCfg(StorageType.File, DiscardPolicy.Old,
                RetentionPolicy.Limits, 10000L, 1, streamName));
        when(jsm.getStreamInfo(streamName)).thenReturn(si);
        // Durable not yet there → notFound, then addOrUpdateConsumer succeeds.
        JetStreamApiException nfConsumer = notFoundException(10014);
        when(jsm.getConsumerInfo(streamName, consumerName)).thenThrow(nfConsumer);
        ConsumerInfo created = mock(ConsumerInfo.class);
        when(jsm.addOrUpdateConsumer(eq(streamName), any(ConsumerConfiguration.class))).thenReturn(created);

        JetStreamSubscription sub = mock(JetStreamSubscription.class);
        when(js.subscribe(eq(subject), any(PullSubscribeOptions.class))).thenReturn(sub);
        // Block all fetches indefinitely — we just want to verify the subscribe wiring.
        when(sub.fetch(anyInt(), any(java.time.Duration.class))).thenReturn(Collections.emptyList());

        NatsJetStreamReliableBroker b = newBroker("node-A");
        ClusterSubscription cs = b.reliableSubscribe("/ws/sub", "node-B", e -> {});
        assertNotNull(cs);
        assertTrue(cs.isActive());

        // Verify the durable consumer was created with the right config.
        ArgumentCaptor<ConsumerConfiguration> cap = ArgumentCaptor.forClass(ConsumerConfiguration.class);
        verify(jsm).addOrUpdateConsumer(eq(streamName), cap.capture());
        ConsumerConfiguration cc = cap.getValue();
        assertEquals(consumerName, cc.getDurable());
        assertEquals(AckPolicy.Explicit, cc.getAckPolicy());
        assertEquals(DeliverPolicy.All, cc.getDeliverPolicy());
        assertEquals(subject, cc.getFilterSubject());

        cs.unsubscribe();
        b.shutdown();
    }

    /** A mocked JetStream message that ack-counts and lets us preset the data + stream sequence. */
    private static Message msg(byte[] data, long streamSeq, AtomicInteger ackCounter) {
        Message m = mock(Message.class);
        when(m.getData()).thenReturn(data);
        NatsJetStreamMetaData meta = mock(NatsJetStreamMetaData.class);
        when(meta.streamSequence()).thenReturn(streamSeq);
        when(m.metaData()).thenReturn(meta);
        doAnswer(inv -> { ackCounter.incrementAndGet(); return null; }).when(m).ack();
        return m;
    }

    @Test
    void t4_consumePath_originSelfSuppress_acksAndSkipsListener() {
        NatsJetStreamReliableBroker b = newBroker("node-A");
        AtomicInteger acks = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        ClusterMessageListener listener = e -> received.incrementAndGet();

        // Build a real envelope from node-A (the local node id) — must be self-suppressed.
        ClusterEnvelope env = envelope("node-A", "/ws/x", "hi");
        byte[] data = codec.encode(env).getBytes(StandardCharsets.UTF_8);
        Message m = msg(data, 1L, acks);

        b.handleMessage(m, "/ws/x", listener, new NatsJetStreamReliableBroker.DedupRing(64));

        assertEquals(0, received.get(), "origin self-suppress: listener must not be invoked");
        assertEquals(1, acks.get(), "must ack on origin self-suppress to clear PEL");
        b.shutdown();
    }

    @Test
    void t4_consumePath_dedupWindow_redeliveryAckedWithoutInvokingListener() {
        NatsJetStreamReliableBroker b = newBroker("node-A");
        AtomicInteger acks = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        ClusterMessageListener listener = e -> received.incrementAndGet();

        // Envelope from a DIFFERENT origin so it is not self-suppressed.
        ClusterEnvelope env = envelope("node-B", "/ws/x", "hi");
        byte[] data = codec.encode(env).getBytes(StandardCharsets.UTF_8);
        NatsJetStreamReliableBroker.DedupRing dedup = new NatsJetStreamReliableBroker.DedupRing(64);

        Message m1 = msg(data, 42L, acks);
        Message m1Redeliver = msg(data, 42L, acks);   // same stream seq → dedup key collision

        b.handleMessage(m1, "/ws/x", listener, dedup);
        b.handleMessage(m1Redeliver, "/ws/x", listener, dedup);

        assertEquals(1, received.get(), "listener invoked exactly once across redeliveries");
        assertEquals(2, acks.get(), "both deliveries must be ACKed");
        b.shutdown();
    }

    @Test
    void t4_consumePath_oversizedInbound_isAckedWithoutDelivery() {
        NatsJetStreamReliableBroker b = newBroker("node-A");
        b.setInboundMaxBytes(10);   // tiny — any sane envelope blows past this
        AtomicInteger acks = new AtomicInteger();
        AtomicInteger received = new AtomicInteger();
        ClusterMessageListener listener = e -> received.incrementAndGet();

        ClusterEnvelope env = envelope("node-B", "/ws/x", "this-is-much-longer-than-ten-bytes");
        byte[] data = codec.encode(env).getBytes(StandardCharsets.UTF_8);
        assertTrue(data.length > 10, "test envelope must exceed the size guard");
        Message m = msg(data, 1L, acks);

        b.handleMessage(m, "/ws/x", listener, new NatsJetStreamReliableBroker.DedupRing(64));

        assertEquals(0, received.get(), "oversized message must not be delivered");
        assertEquals(1, acks.get(), "oversized message must be ACKed (drop from PEL, no nak)");
        b.shutdown();
    }

    @Test
    void t4_consumePath_poisonPillListenerThrows_messageStillAcked() {
        NatsJetStreamReliableBroker b = newBroker("node-A");
        AtomicInteger acks = new AtomicInteger();
        ClusterMessageListener listener = e -> { throw new RuntimeException("boom"); };

        ClusterEnvelope env = envelope("node-B", "/ws/x", "poison");
        byte[] data = codec.encode(env).getBytes(StandardCharsets.UTF_8);
        Message m = msg(data, 7L, acks);

        b.handleMessage(m, "/ws/x", listener, new NatsJetStreamReliableBroker.DedupRing(64));

        assertEquals(1, acks.get(), "poison-pill: listener throw must still ack to avoid livelock");
        b.shutdown();
    }

    // ===== T5 — Connection listener state CAS =====

    @Test
    void t5_connectionListener_flipsStateOnDisconnectAndReconnect() {
        NatsJetStreamReliableBroker b = newBroker();
        ConnectionListener l = b.connectionListenerForTest();
        assertNotNull(l);
        assertEquals(BrokerState.ACTIVE, b.state());

        l.connectionEvent(conn, ConnectionListener.Events.DISCONNECTED);
        assertEquals(BrokerState.DEGRADED, b.state());

        l.connectionEvent(conn, ConnectionListener.Events.RECONNECTED);
        assertEquals(BrokerState.ACTIVE, b.state());

        b.shutdown();
        // After shutdown the listener must never overwrite SHUTDOWN.
        l.connectionEvent(conn, ConnectionListener.Events.DISCONNECTED);
        assertEquals(BrokerState.SHUTDOWN, b.state());
    }

    /** S1 (RC16): RECONNECTED must clear the per-URI ensureStream cache so the next publish
     *  re-validates the stream's existence — defends against NATS losing data while we were down. */
    @Test
    void connectionListener_clearsStreamCacheOnReconnect() throws Exception {
        String b64uri = b64("/ws/s1");
        String streamName = "netty-cluster-reliable-" + b64uri;
        StreamInfo si = streamInfoWith(streamCfg(StorageType.File, DiscardPolicy.Old,
                RetentionPolicy.Limits, 10000L, 1, streamName));
        when(jsm.getStreamInfo(streamName)).thenReturn(si);
        when(js.publishAsync(anyString(), any(byte[].class)))
                .thenReturn(CompletableFuture.completedFuture(null));

        NatsJetStreamReliableBroker b = newBroker("node-A");

        // Seed the per-URI streamCache by completing a successful publish.
        b.reliablePublish("/ws/s1", envelope("node-A", "/ws/s1", "seed"));
        verify(jsm, times(1)).getStreamInfo(streamName);

        // A second publish without reconnect must NOT re-invoke getStreamInfo (cache is warm) —
        // sanity check that proves the first call was actually cached.
        b.reliablePublish("/ws/s1", envelope("node-A", "/ws/s1", "still-cached"));
        verify(jsm, times(1)).getStreamInfo(streamName);

        // Fire RECONNECTED through the captured listener.
        ConnectionListener l = b.connectionListenerForTest();
        assertNotNull(l);
        l.connectionEvent(conn, ConnectionListener.Events.RECONNECTED);

        // Next publish must re-invoke getStreamInfo (cache cleared by the reconnect handler).
        b.reliablePublish("/ws/s1", envelope("node-A", "/ws/s1", "post-reconnect"));
        verify(jsm, times(2)).getStreamInfo(streamName);

        b.shutdown();
    }

    // ===== T6 — destroyConsumerGroupsForNode with idle gate =====

    private static ConsumerInfo consumerInfo(long numPending, ZonedDateTime lastActive) {
        ConsumerInfo info = mock(ConsumerInfo.class);
        when(info.getNumPending()).thenReturn(numPending);
        SequenceInfo seq = mock(SequenceInfo.class);
        when(seq.getLastActive()).thenReturn(lastActive);
        when(info.getDelivered()).thenReturn(seq);
        return info;
    }

    @Test
    void t6_destroyConsumerGroupsForNode_reapsIdleZeroPending_retainsRecentOrPending() throws Exception {
        String dead = "node-dead";
        String consumerName = "g_" + b64(dead);
        String streamIdle = "netty-cluster-reliable-" + b64("/ws/idle");
        String streamBusy = "netty-cluster-reliable-" + b64("/ws/busy");
        String streamRecent = "netty-cluster-reliable-" + b64("/ws/recent");

        ConsumerInfo idleInfo = consumerInfo(0L, ZonedDateTime.now().minusHours(2));
        ConsumerInfo busyInfo = consumerInfo(5L, ZonedDateTime.now().minusHours(2));
        ConsumerInfo recentInfo = consumerInfo(0L, ZonedDateTime.now().minusMinutes(1));
        when(jsm.getStreamNames()).thenReturn(Arrays.asList(streamIdle, streamBusy, streamRecent, "unrelated-stream"));
        when(jsm.getConsumerInfo(streamIdle, consumerName)).thenReturn(idleInfo);
        when(jsm.getConsumerInfo(streamBusy, consumerName)).thenReturn(busyInfo);
        when(jsm.getConsumerInfo(streamRecent, consumerName)).thenReturn(recentInfo);
        when(jsm.deleteConsumer(anyString(), anyString())).thenReturn(true);

        NatsJetStreamReliableBroker b = newBroker("node-A");
        b.setGroupDestroyIdleMs(java.time.Duration.ofHours(1).toMillis());
        b.destroyConsumerGroupsForNode(dead);
        b.shutdown();

        verify(jsm, times(1)).deleteConsumer(streamIdle, consumerName);
        verify(jsm, never()).deleteConsumer(streamBusy, consumerName);
        verify(jsm, never()).deleteConsumer(streamRecent, consumerName);
        // Unrelated stream prefix must never even be queried.
        verify(jsm, never()).getConsumerInfo(eq("unrelated-stream"), anyString());
    }

    @Test
    void t6_destroyConsumerGroupsForNode_idleZero_retainsEverything() throws Exception {
        when(jsm.getStreamNames()).thenReturn(Collections.singletonList(
                "netty-cluster-reliable-" + b64("/ws/x")));

        NatsJetStreamReliableBroker b = newBroker("node-A");
        b.setGroupDestroyIdleMs(0);   // pure-retain mode
        b.destroyConsumerGroupsForNode("node-dead");
        b.shutdown();

        verify(jsm, never()).deleteConsumer(anyString(), anyString());
        verify(jsm, never()).getConsumerInfo(anyString(), anyString());
    }

    @Test
    void t6_destroyConsumerGroupsForNode_consumerNotFound_silentlyContinues() throws Exception {
        String streamA = "netty-cluster-reliable-" + b64("/ws/a");
        JetStreamApiException nf = notFoundException(10014);
        when(jsm.getStreamNames()).thenReturn(Collections.singletonList(streamA));
        when(jsm.getConsumerInfo(eq(streamA), anyString())).thenThrow(nf);

        NatsJetStreamReliableBroker b = newBroker("node-A");
        b.destroyConsumerGroupsForNode("node-dead");
        b.shutdown();

        verify(jsm, never()).deleteConsumer(anyString(), anyString());
    }
}
