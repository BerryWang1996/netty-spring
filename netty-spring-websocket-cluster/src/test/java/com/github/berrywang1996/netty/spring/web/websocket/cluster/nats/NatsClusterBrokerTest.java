package com.github.berrywang1996.netty.spring.web.websocket.cluster.nats;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.BrokerState;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterBrokerException;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterMessageListener;
import io.nats.client.Connection;
import io.nats.client.ConnectionListener;
import io.nats.client.Dispatcher;
import io.nats.client.Message;
import io.nats.client.MessageHandler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class NatsClusterBrokerTest {

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static ClusterEnvelope env(String origin, String uri) {
        return new ClusterEnvelope(origin, uri, ClusterEnvelope.MessageKind.BROADCAST,
                "T:hi".getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis());
    }

    @Test
    void publishesToBase64UrlBroadcastSubject_andStateActive() {
        Connection conn = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        when(conn.createDispatcher(any())).thenReturn(dispatcher);

        NatsClusterBroker broker = new NatsClusterBroker(new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator());
        broker.attach(conn);

        assertEquals(BrokerState.ACTIVE, broker.state());
        broker.publish("/ws/x", env("node-A", "/ws/x"));
        verify(conn).publish(eq("netty.broadcast." + b64("/ws/x")), any(byte[].class));
    }

    @Test
    void subscribeRoutesToBroadcastSubject() {
        Connection conn = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        when(conn.createDispatcher(any())).thenReturn(dispatcher);

        NatsClusterBroker broker = new NatsClusterBroker(new SimpleTextEnvelopeCodec());
        broker.attach(conn);
        broker.subscribe("/ws/x", e -> { });
        verify(dispatcher).subscribe("netty.broadcast." + b64("/ws/x"));
    }

    @Test
    void inboundMessageRoutesToTheRegisteredListener() throws Exception {
        Connection conn = mock(Connection.class);
        Dispatcher dispatcher = mock(Dispatcher.class);
        when(conn.createDispatcher(any())).thenReturn(dispatcher);

        NatsClusterBroker broker = new NatsClusterBroker(new SimpleTextEnvelopeCodec());
        // capture the dispatcher MessageHandler so we can feed it a message
        ArgumentCaptor<MessageHandler> handlerCap = ArgumentCaptor.forClass(MessageHandler.class);
        broker.attach(conn);
        verify(conn).createDispatcher(handlerCap.capture());

        List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
        broker.subscribe("/ws/x", got::add);

        String subject = "netty.broadcast." + b64("/ws/x");
        byte[] payload = new SimpleTextEnvelopeCodec().encode(env("node-A", "/ws/x")).getBytes(StandardCharsets.UTF_8);
        Message msg = mock(Message.class);
        when(msg.getSubject()).thenReturn(subject);
        when(msg.getData()).thenReturn(payload);
        handlerCap.getValue().onMessage(msg);

        assertEquals(1, got.size());
        assertEquals("node-A", got.get(0).getOriginNodeId());
    }

    @Test
    void publishWrapsRawJnatsExceptionInClusterBrokerException() {
        Connection conn = mock(Connection.class);
        when(conn.createDispatcher(any())).thenReturn(mock(Dispatcher.class));
        // jnats Connection.publish throws unchecked IllegalStateException (closed / reconnect-buffer full).
        doThrow(new IllegalStateException("connection closed"))
                .when(conn).publish(anyString(), any(byte[].class));

        NatsClusterBroker broker = new NatsClusterBroker(new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator());
        broker.attach(conn);

        ClusterBrokerException ex = assertThrows(ClusterBrokerException.class,
                () -> broker.publish("/ws/x", env("node-A", "/ws/x")),
                "raw jnats IllegalStateException must be wrapped so onPublishFailure can handle it");
        assertTrue(ex.getCause() instanceof IllegalStateException);
    }

    @Test
    void unicastWrapsRawJnatsExceptionInClusterBrokerException() {
        Connection conn = mock(Connection.class);
        when(conn.createDispatcher(any())).thenReturn(mock(Dispatcher.class));
        // IllegalArgumentException = payload exceeds server max-payload.
        doThrow(new IllegalArgumentException("payload too large"))
                .when(conn).publish(anyString(), any(byte[].class));

        NatsClusterBroker broker = new NatsClusterBroker(new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator());
        broker.attach(conn);

        ClusterBrokerException ex = assertThrows(ClusterBrokerException.class,
                () -> broker.unicast("node-B", env("node-A", "/ws/x")),
                "raw jnats IllegalArgumentException must be wrapped so onPublishFailure can handle it");
        assertTrue(ex.getCause() instanceof IllegalArgumentException);
    }

    @Test
    void connectionEventsDriveBrokerState() {
        Connection conn = mock(Connection.class);
        when(conn.createDispatcher(any())).thenReturn(mock(Dispatcher.class));
        NatsClusterBroker broker = new NatsClusterBroker(new SimpleTextEnvelopeCodec());
        broker.attach(conn);

        broker.onConnectionEvent(conn, ConnectionListener.Events.DISCONNECTED);
        assertEquals(BrokerState.DEGRADED, broker.state());
        broker.onConnectionEvent(conn, ConnectionListener.Events.RECONNECTED);
        assertEquals(BrokerState.ACTIVE, broker.state());
    }
}
