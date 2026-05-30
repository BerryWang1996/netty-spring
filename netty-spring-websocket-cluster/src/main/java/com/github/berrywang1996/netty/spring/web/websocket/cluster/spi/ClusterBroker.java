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

package com.github.berrywang1996.netty.spring.web.websocket.cluster.spi;

/**
 * SPI for cross-node message transport (fan-out broadcast + targeted unicast).
 *
 * <p>This is the primary extension point for plugging in different cluster transports.
 * The default implementation in 1.8.0 is {@code RedisPubSubBroker} (Redis Pub/Sub);
 * future implementations include NATS (interest-based routing) and node-mesh
 * (direct Netty TCP connections, registry-only on Redis).
 *
 * <p>{@code ClusterMessageSender} depends only on this interface and
 * {@link SessionRegistry} — never directly on Lettuce or any transport-specific class.
 * This is the "cheap now, impossible to retrofit later" structural decision (ADR-001).
 *
 * <h3>Threading contract</h3>
 * <ul>
 *   <li>{@link #publish} and {@link #unicast} may be called from any thread (handler pool,
 *       event loop, sender pool). Implementations must be thread-safe.</li>
 *   <li>{@link ClusterMessageListener#onMessage} is invoked on the transport's I/O thread
 *       (e.g. Lettuce event loop). Listeners must not block; heavy work should be dispatched
 *       to a separate executor.</li>
 * </ul>
 *
 * @author berrywang1996
 * @since V1.8.0
 * @see SessionRegistry
 * @see ClusterEnvelope
 */
public interface ClusterBroker {

    /**
     * Publishes a broadcast envelope to all nodes subscribing to the given URI.
     *
     * <p>The envelope's {@link ClusterEnvelope#getOriginNodeId()} is used by subscribers
     * to suppress self-delivery (the origin node has already performed local fan-out
     * before calling this method).
     *
     * <p><b>Delivery guarantee:</b> at-most-once (Redis Pub/Sub default). For at-least-once,
     * use the reliable stream path (separate API, not part of this SPI in 1.8.0).
     *
     * @param uri      the WebSocket mapping URI to publish on
     * @param envelope the message envelope (must have {@code kind == BROADCAST})
     * @throws ClusterBrokerException if the publish fails (e.g. Redis connection lost)
     */
    void publish(String uri, ClusterEnvelope envelope);

    /**
     * Sends a unicast envelope to a specific target node.
     *
     * <p>The target node is identified by {@code targetNodeId}; the envelope's
     * {@link ClusterEnvelope#getTargetSessionId()} tells the receiver which local session
     * to deliver to.
     *
     * @param targetNodeId the node id that owns the target session
     * @param envelope     the message envelope (must have {@code kind == UNICAST})
     * @throws ClusterBrokerException if the send fails
     */
    void unicast(String targetNodeId, ClusterEnvelope envelope);

    /**
     * Subscribes to broadcast messages for a URI. Returns a handle that can be used
     * to unsubscribe.
     *
     * <p>Implementations should support multiple concurrent subscriptions to different URIs.
     * Subscribing to the same URI twice is idempotent (the listener is not duplicated).
     *
     * @param uri      the WebSocket mapping URI to subscribe to
     * @param listener the callback for incoming broadcast envelopes
     * @return a subscription handle; call {@link ClusterSubscription#unsubscribe()} to stop
     */
    ClusterSubscription subscribe(String uri, ClusterMessageListener listener);

    /**
     * Subscribes to unicast messages targeted at this node. Each node should call this
     * once during startup.
     *
     * @param nodeId   this node's unique identifier
     * @param listener the callback for incoming unicast envelopes
     * @return a subscription handle
     */
    ClusterSubscription subscribeUnicast(String nodeId, ClusterMessageListener listener);

    /**
     * Async variant of {@link #publish} that returns a completion stage. Implementations
     * that support async confirmation (e.g. NATS JetStream) should override this;
     * the default delegates to {@link #publish} and returns a completed stage.
     *
     * @since V1.8.0
     */
    default java.util.concurrent.CompletionStage<Void> publishAsync(String uri, ClusterEnvelope envelope) {
        publish(uri, envelope);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /**
     * Async variant of {@link #unicast}.
     *
     * @since V1.8.0
     */
    default java.util.concurrent.CompletionStage<Void> unicastAsync(String targetNodeId, ClusterEnvelope envelope) {
        unicast(targetNodeId, envelope);
        return java.util.concurrent.CompletableFuture.completedFuture(null);
    }

    /**
     * Returns the current state of this broker's connection to the underlying transport.
     *
     * @return the broker state (never null)
     */
    BrokerState state();

    /**
     * Listener notified the instant the underlying transport connection is lost or restored,
     * so the cluster can degrade/recover immediately (event-driven) instead of waiting for the
     * periodic heartbeat probe to notice.
     *
     * @since V1.8.0
     */
    interface TransportStateListener {
        /** Transport connection lost (e.g. Redis disconnected). */
        void onTransportLost();
        /** Transport connection restored. */
        void onTransportRestored();
    }

    /**
     * Registers a {@link TransportStateListener} to be notified on transport connect/disconnect
     * events. Implementations that can detect connection state (e.g. via a Lettuce
     * {@code RedisConnectionStateListener}) should override this and also keep {@link #state()}
     * truthful. The default is a no-op — degradation then relies solely on the heartbeat probe.
     *
     * @param listener the listener (never null)
     * @since V1.8.0
     */
    default void setTransportStateListener(TransportStateListener listener) {
        // no-op by default; transports without connection-state events rely on the heartbeat probe
    }

    /**
     * Shuts down the broker, releasing all transport resources.
     * After shutdown, all publish/unicast/subscribe calls will throw.
     */
    void shutdown();
}
