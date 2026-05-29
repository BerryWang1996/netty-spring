package com.github.berrywang1996.netty.spring.web.websocket.cluster;

import com.github.berrywang1996.netty.spring.web.websocket.cluster.spi.*;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * In-memory stub implementation of {@link ClusterBroker} for testing SPI isolation.
 * Proves that {@link ClusterMessageSender} does not depend on Lettuce or any
 * transport-specific class.
 */
public class InMemoryBroker implements ClusterBroker {

    private final ConcurrentHashMap<String, List<ClusterMessageListener>> broadcastListeners = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ClusterMessageListener> unicastListeners = new ConcurrentHashMap<>();
    private volatile BrokerState state = BrokerState.ACTIVE;
    private final List<ClusterEnvelope> publishedEnvelopes = new CopyOnWriteArrayList<>();

    @Override
    public void publish(String uri, ClusterEnvelope envelope) {
        if (state != BrokerState.ACTIVE) {
            throw new ClusterBrokerException("Broker not active: " + state);
        }
        publishedEnvelopes.add(envelope);
        List<ClusterMessageListener> listeners = broadcastListeners.get(uri);
        if (listeners != null) {
            for (ClusterMessageListener l : listeners) {
                l.onMessage(envelope);
            }
        }
    }

    @Override
    public void unicast(String targetNodeId, ClusterEnvelope envelope) {
        if (state != BrokerState.ACTIVE) {
            throw new ClusterBrokerException("Broker not active: " + state);
        }
        ClusterMessageListener listener = unicastListeners.get(targetNodeId);
        if (listener != null) {
            listener.onMessage(envelope);
        }
    }

    @Override
    public ClusterSubscription subscribe(String uri, ClusterMessageListener listener) {
        broadcastListeners.computeIfAbsent(uri, k -> new CopyOnWriteArrayList<>()).add(listener);
        AtomicBoolean active = new AtomicBoolean(true);
        return new ClusterSubscription() {
            @Override
            public void unsubscribe() {
                active.set(false);
                List<ClusterMessageListener> list = broadcastListeners.get(uri);
                if (list != null) list.remove(listener);
            }

            @Override
            public boolean isActive() {
                return active.get();
            }
        };
    }

    @Override
    public ClusterSubscription subscribeUnicast(String nodeId, ClusterMessageListener listener) {
        unicastListeners.put(nodeId, listener);
        AtomicBoolean active = new AtomicBoolean(true);
        return new ClusterSubscription() {
            @Override
            public void unsubscribe() {
                active.set(false);
                unicastListeners.remove(nodeId);
            }

            @Override
            public boolean isActive() {
                return active.get();
            }
        };
    }

    @Override
    public BrokerState state() {
        return state;
    }

    @Override
    public void shutdown() {
        state = BrokerState.SHUTDOWN;
        broadcastListeners.clear();
        unicastListeners.clear();
    }

    /** Test helper: get all published envelopes. */
    public List<ClusterEnvelope> getPublishedEnvelopes() {
        return publishedEnvelopes;
    }

    /** Test helper: set broker state. */
    public void setState(BrokerState newState) {
        this.state = newState;
    }
}
