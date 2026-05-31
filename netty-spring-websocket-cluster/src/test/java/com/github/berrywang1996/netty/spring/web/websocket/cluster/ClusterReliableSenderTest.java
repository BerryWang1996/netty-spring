package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeHeartbeat;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.node.ClusterNodeManager;
import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.ClusterEnvelope;
import com.github.berrywang1996.netty.spring.web.websocket.context.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Sender-side reliable-broadcast behavior, against the in-memory broker (no Redis). */
class ClusterReliableSenderTest {

    private InMemoryBroker broker;
    private InMemorySessionRegistry registry;
    private CountingLocalSender localSender;
    private ClusterNodeManager nodeManager;
    private ClusterMessageSender sender;
    private InMemoryReliableBroker reliableBroker;

    static class CountingLocalSender implements MessageSender {
        final AtomicInteger topicCount = new AtomicInteger();
        final Set<String> uris = new HashSet<>();
        void addUri(String u) { uris.add(u); }
        @Override public int getSessionNums() { return 0; }
        @Override public int getSessionNums(String uri) { return 0; }
        @Override public Set<String> getRegisteredUri() { return Collections.unmodifiableSet(uris); }
        @Override public boolean isSessionAlive(String uri, String... ids) { return false; }
        @Override public void sendMessage(String uri, AbstractMessage m, String... ids) {}
        @Override public void topicMessage(String uri, AbstractMessage m) { topicCount.incrementAndGet(); }
    }
    static class NoOpHeartbeat implements ClusterNodeHeartbeat {
        @Override public void register(String n, long t) {}
        @Override public void renewHeartbeat(String n, long t) {}
        @Override public void deregister(String n) {}
        @Override public List<String> findExpiredNodes(long t) { return Collections.emptyList(); }
    }

    @BeforeEach
    void setUp() {
        broker = new InMemoryBroker();
        registry = new InMemorySessionRegistry();
        localSender = new CountingLocalSender();
        nodeManager = new ClusterNodeManager("node-A", 60000, 600000, 60000, 60000, new NoOpHeartbeat(), registry);
        nodeManager.setRedisLossGracePeriodMs(0);
        sender = new ClusterMessageSender(localSender, broker, registry, nodeManager, 5000);
        reliableBroker = new InMemoryReliableBroker();
    }

    @AfterEach
    void tearDown() {
        try { sender.shutdown(); } catch (Exception ignored) {}
        try { nodeManager.shutdown(); } catch (Exception ignored) {}
    }

    @Test
    void reliableBroadcastThrowsWhenDisabled() {
        localSender.addUri("/ws/r");
        nodeManager.start();
        sender.start();
        assertThrows(IllegalStateException.class,
                () -> sender.reliableBroadcast("/ws/r", new TextMessage("x")));
    }

    @Test
    void reliableBroadcastDoesLocalFanOutThenPublishes() {
        localSender.addUri("/ws/r");
        sender.setReliableBroker(reliableBroker);
        nodeManager.start();
        sender.start();
        sender.reliableBroadcast("/ws/r", new TextMessage("hello"));
        assertEquals(1, localSender.topicCount.get(), "local fan-out happens first");
        assertEquals(1, reliableBroker.getPublished().size(), "then it XADDs to the reliable stream");
        assertEquals("node-A", reliableBroker.getPublished().get(0).getOriginNodeId());
        assertEquals(1, sender.getClusterRuntimeStats().getReliablePublished());
    }

    @Test
    void onReliableMessageSuppressesOriginEcho() {
        localSender.addUri("/ws/r");
        sender.setReliableBroker(reliableBroker);
        nodeManager.start();
        sender.start(); // subscribes reliably to /ws/r
        sender.reliableBroadcast("/ws/r", new TextMessage("hello"));
        assertEquals(1, localSender.topicCount.get(),
                "origin's own reliable echo must be suppressed (no double local delivery)");
    }
}
