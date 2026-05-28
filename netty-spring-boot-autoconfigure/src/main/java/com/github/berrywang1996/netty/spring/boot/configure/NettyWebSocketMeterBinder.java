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

package com.github.berrywang1996.netty.spring.boot.configure;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.consts.CloseReason;
import com.github.berrywang1996.netty.spring.web.websocket.context.WebSocketEventRecorder;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;

import java.util.Map;

/**
 * Bridges {@link WebSocketEventRecorder} counters to a Micrometer {@link MeterRegistry}.
 *
 * <p>Registered metrics:
 * <ul>
 *   <li>{@code netty.websocket.handshakes.total} – total handshake attempts (FunctionCounter)</li>
 *   <li>{@code netty.websocket.handshakes.success} – successful handshakes (FunctionCounter)</li>
 *   <li>{@code netty.websocket.handshakes.rejected} – interceptor rejections (FunctionCounter)</li>
 *   <li>{@code netty.websocket.messages.received} – inbound messages (FunctionCounter)</li>
 *   <li>{@code netty.websocket.messages.sent} – outbound messages (FunctionCounter)</li>
 *   <li>{@code netty.websocket.sessions.closed} – session closes, tagged by {@code reason} (FunctionCounter)</li>
 *   <li>{@code netty.websocket.sessions.active} – currently active sessions (Gauge)</li>
 *   <li>{@code netty.websocket.mappings} – registered mapping count (Gauge)</li>
 * </ul>
 *
 * @author berrywang1996
 * @since V1.3.0
 * @see NettyMicrometerConfigure
 */
public class NettyWebSocketMeterBinder implements MeterBinder {

    /** The server bootstrap that holds the WebSocket mapping resolvers and event recorder. */
    private final NettyServerBootstrap bootstrap;

    /**
     * Constructs a new binder that reads WebSocket runtime counters from the given bootstrap.
     *
     * @param bootstrap the Netty server bootstrap instance; must not be {@code null}
     */
    public NettyWebSocketMeterBinder(NettyServerBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    /**
     * Registers all WebSocket-related meters (handshake counters, message counters,
     * session close counters by reason, active session gauge, and mapping count gauge)
     * with the provided {@link MeterRegistry}.
     *
     * <p>If no WebSocket mapping resolvers are registered or the shared
     * {@link WebSocketEventRecorder} cannot be found, this method returns
     * immediately without registering any meters.
     *
     * @param registry the Micrometer meter registry to bind counters and gauges to
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        // Retrieve the map of URI -> resolver for all registered WebSocket endpoints
        Map<String, AbstractMappingResolver> wsMap = bootstrap.getWebSocketMappingResolverMap();
        if (wsMap == null || wsMap.isEmpty()) {
            return;
        }

        // Find the shared event recorder (all resolvers share the same instance)
        WebSocketEventRecorder recorder = findEventRecorder(wsMap);
        if (recorder == null) {
            return;
        }

        // ---- Handshake counters ----
        FunctionCounter.builder("netty.websocket.handshakes.total", recorder,
                        r -> r.getStats().getHandshakeTotal())
                .description("Total WebSocket handshake attempts")
                .register(registry);

        FunctionCounter.builder("netty.websocket.handshakes.success", recorder,
                        r -> r.getStats().getHandshakeSuccess())
                .description("Successful WebSocket handshakes")
                .register(registry);

        FunctionCounter.builder("netty.websocket.handshakes.rejected", recorder,
                        r -> r.getStats().getHandshakeRejectedByInterceptor())
                .description("WebSocket handshakes rejected by interceptor")
                .register(registry);

        // ---- Message counters ----
        FunctionCounter.builder("netty.websocket.messages.received", recorder,
                        r -> r.getStats().getMessagesReceived())
                .description("Total WebSocket messages received")
                .register(registry);

        FunctionCounter.builder("netty.websocket.messages.sent", recorder,
                        r -> r.getStats().getMessagesSent())
                .description("Total WebSocket messages sent")
                .register(registry);

        // ---- Close counters by reason: one counter per CloseReason enum value, tagged ----
        for (CloseReason reason : CloseReason.values()) {
            FunctionCounter.builder("netty.websocket.sessions.closed", recorder,
                            r -> r.getStats().getCloseCount(reason))
                    .tag("reason", reason.getTag())
                    .description("WebSocket sessions closed")
                    .register(registry);
        }

        // ---- Active session gauge: sums active sessions across all mapping resolvers ----
        Gauge.builder("netty.websocket.sessions.active", wsMap, map -> {
                    int total = 0;
                    for (AbstractMappingResolver resolver : map.values()) {
                        total += Math.max(0, resolver.getActiveSessionCount());
                    }
                    return total;
                })
                .description("Currently active WebSocket sessions")
                .register(registry);

        // ---- Mapping count gauge: number of registered WebSocket URI mappings ----
        Gauge.builder("netty.websocket.mappings", wsMap, Map::size)
                .description("Number of registered WebSocket mappings")
                .register(registry);
    }

    /**
     * Searches the WebSocket mapping resolver map for a {@link MessageMappingResolver}
     * that holds a non-null {@link WebSocketEventRecorder}.
     *
     * <p>All resolvers typically share the same recorder instance, so the first
     * one found is returned.
     *
     * @param wsMap the map of URI paths to their corresponding mapping resolvers
     * @return the shared {@link WebSocketEventRecorder}, or {@code null} if none
     *         of the resolvers are {@link MessageMappingResolver} instances or
     *         none have a recorder set
     */
    private static WebSocketEventRecorder findEventRecorder(Map<String, AbstractMappingResolver> wsMap) {
        for (AbstractMappingResolver resolver : wsMap.values()) {
            if (resolver instanceof MessageMappingResolver) {
                WebSocketEventRecorder recorder = ((MessageMappingResolver) resolver).getEventRecorder();
                if (recorder != null) {
                    return recorder;
                }
            }
        }
        return null;
    }
}
