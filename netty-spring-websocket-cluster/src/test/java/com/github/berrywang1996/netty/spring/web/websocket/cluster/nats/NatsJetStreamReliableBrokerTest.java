package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.EnvelopeCodec;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.JetStream;
import io.nats.client.JetStreamManagement;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
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
}
