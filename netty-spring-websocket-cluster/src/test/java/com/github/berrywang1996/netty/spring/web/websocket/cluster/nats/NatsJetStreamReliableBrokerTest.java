package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBrokerException;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.MessageAuthenticator;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamApiException;
import io.nats.client.JetStreamManagement;
import io.nats.client.api.DiscardPolicy;
import io.nats.client.api.Error;
import io.nats.client.api.RetentionPolicy;
import io.nats.client.api.StorageType;
import io.nats.client.api.StreamConfiguration;
import io.nats.client.api.StreamInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
}
