package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-memory {@link ReliableBroker} stub for SPI-isolation unit tests (no Redis). */
public class InMemoryReliableBroker implements ReliableBroker {

    private final List<ClusterEnvelope> published = new CopyOnWriteArrayList<>();
    private final ConcurrentHashMap<String, ClusterMessageListener> listeners = new ConcurrentHashMap<>();
    private volatile BrokerState state = BrokerState.ACTIVE;
    private final List<String> destroyedForNodes = new CopyOnWriteArrayList<>();

    @Override
    public void reliablePublish(String uri, ClusterEnvelope envelope) {
        published.add(envelope);
        // Echo to a subscribed listener on the same URI (mimics every node — incl. origin — consuming).
        ClusterMessageListener l = listeners.get(uri);
        if (l != null) l.onMessage(envelope);
    }

    @Override
    public ClusterSubscription reliableSubscribe(String uri, String nodeId, ClusterMessageListener listener) {
        listeners.put(uri, listener);
        AtomicBoolean active = new AtomicBoolean(true);
        return new ClusterSubscription() {
            @Override public void unsubscribe() { active.set(false); listeners.remove(uri); }
            @Override public boolean isActive() { return active.get(); }
        };
    }

    @Override public void destroyConsumerGroupsForNode(String nodeId) { destroyedForNodes.add(nodeId); }
    @Override public BrokerState state() { return state; }
    @Override public void shutdown() { state = BrokerState.SHUTDOWN; listeners.clear(); }

    // ---- test helpers ----
    public List<ClusterEnvelope> getPublished() { return published; }
    public List<String> getDestroyedForNodes() { return destroyedForNodes; }
    public void setState(BrokerState s) { this.state = s; }
}
