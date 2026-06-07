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
    private volatile TransportStateListener transportStateListener;

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
    public void setTransportStateListener(TransportStateListener listener) {
        this.transportStateListener = listener;
    }

    /** Test helper: simulate a transport drop/restore by invoking the registered listener. */
    public void fireTransportLost() {
        if (transportStateListener != null) transportStateListener.onTransportLost();
    }

    public void fireTransportRestored() {
        if (transportStateListener != null) transportStateListener.onTransportRestored();
    }

    public boolean hasTransportStateListener() {
        return transportStateListener != null;
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
