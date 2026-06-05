package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.auth.NoOpMessageAuthenticator;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.codec.SimpleTextEnvelopeCodec;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.nats.NatsClusterBroker;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import io.nats.client.Connection;
import io.nats.client.Nats;
import io.nats.client.Options;
import org.junit.jupiter.api.*;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class NatsIntegrationTest {

    private static boolean natsAvailable;

    @BeforeAll
    static void up() {
        natsAvailable = ClusterTestNats.available();
    }

    private static NatsClusterBroker broker() throws Exception {
        NatsClusterBroker b = new NatsClusterBroker(new SimpleTextEnvelopeCodec(), new NoOpMessageAuthenticator());
        Connection c = Nats.connect(Options.builder().server(ClusterTestNats.url()).build());
        b.attach(c);
        return b;
    }

    @Test
    void broadcastPublishReachesSubscriber() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "no NATS");
        NatsClusterBroker a = broker();
        NatsClusterBroker b = broker();
        try {
            List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            b.subscribe("/ws/nbc", e -> { got.add(e); latch.countDown(); });
            Thread.sleep(300); // subscription propagation
            a.publish("/ws/nbc", new ClusterEnvelope("node-A", "/ws/nbc", ClusterEnvelope.MessageKind.BROADCAST,
                    "T:hello".getBytes(StandardCharsets.UTF_8), null, null, System.currentTimeMillis()));
            assertTrue(latch.await(6, TimeUnit.SECONDS), "subscriber must receive the NATS broadcast");
            assertEquals(1, got.size());
            assertEquals("node-A", got.get(0).getOriginNodeId());
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }

    @Test
    void unicastPublishReachesTargetNode() throws Exception {
        Assumptions.assumeTrue(natsAvailable, "no NATS");
        NatsClusterBroker a = broker();
        NatsClusterBroker b = broker();
        try {
            List<ClusterEnvelope> got = new CopyOnWriteArrayList<>();
            CountDownLatch latch = new CountDownLatch(1);
            b.subscribeUnicast("node-B", e -> { got.add(e); latch.countDown(); });
            Thread.sleep(300);
            a.unicast("node-B", new ClusterEnvelope("node-A", "/ws/nbc", ClusterEnvelope.MessageKind.UNICAST,
                    "T:dm".getBytes(StandardCharsets.UTF_8), "sess-1", null, System.currentTimeMillis()));
            assertTrue(latch.await(6, TimeUnit.SECONDS), "target node must receive the NATS unicast");
            assertEquals(1, got.size());
            assertEquals("sess-1", got.get(0).getTargetSessionId());
        } finally {
            a.shutdown();
            b.shutdown();
        }
    }
}
