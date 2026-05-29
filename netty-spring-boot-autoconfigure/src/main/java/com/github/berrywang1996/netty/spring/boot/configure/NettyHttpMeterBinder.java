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

import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.netty.buffer.PooledByteBufAllocator;

/**
 * Bridges {@link com.github.berrywang1996.netty.spring.web.context.HttpRuntimeRecorder}
 * counters to a Micrometer {@link MeterRegistry}.
 *
 * <p>Registered metrics:
 * <ul>
 *   <li>{@code netty.http.response.write.failures} – HTTP response write failures (FunctionCounter)</li>
 *   <li>{@code netty.http.static.rejected} – static file requests rejected (path traversal, etc.) (FunctionCounter)</li>
 *   <li>{@code netty.http.static.write.failures} – static file write failures (FunctionCounter)</li>
 *   <li>{@code netty.http.idle.closes} – idle connection closes (FunctionCounter)</li>
 *   <li>{@code netty.http.websocket.handshake.rejected} – WebSocket handshake rejects at HTTP level (FunctionCounter)</li>
 *   <li>{@code netty.http.websocket.origin.rejected} – WebSocket origin check rejections (FunctionCounter)</li>
 *   <li>{@code netty.handler.pool.size} – current handler thread pool size (Gauge)</li>
 *   <li>{@code netty.handler.pool.active} – active handler threads (Gauge)</li>
 *   <li>{@code netty.handler.pool.core} – core handler pool size (Gauge)</li>
 *   <li>{@code netty.handler.pool.max} – maximum handler pool size (Gauge)</li>
 *   <li>{@code netty.handler.queue.size} – handler work queue depth (Gauge)</li>
 *   <li>{@code netty.handler.queue.remaining} – handler work queue remaining capacity (Gauge)</li>
 *   <li>{@code netty.handler.permits.available} – available admission permits (Gauge)</li>
 *   <li>{@code netty.handler.permits.limit} – maximum admission permits (Gauge)</li>
 *   <li>{@code netty.allocator.used.heap} – heap memory used by Netty allocator (Gauge)</li>
 *   <li>{@code netty.allocator.used.direct} – direct memory used by Netty allocator (Gauge)</li>
 * </ul>
 *
 * @author berrywang1996
 * @since V1.3.0
 * @see NettyMicrometerConfigure
 */
public class NettyHttpMeterBinder implements MeterBinder {

    /** The server bootstrap whose HTTP runtime stats are exposed as meters. */
    private final NettyServerBootstrap bootstrap;

    /**
     * Constructs a new binder that reads HTTP runtime counters from the given bootstrap.
     *
     * @param bootstrap the Netty server bootstrap instance; must not be {@code null}
     */
    public NettyHttpMeterBinder(NettyServerBootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    /**
     * Registers all HTTP-related {@link FunctionCounter} meters with the provided
     * {@link MeterRegistry}. Each counter is backed by a lambda that reads the
     * current value from the bootstrap's HTTP runtime statistics, so the counters
     * always reflect the live server state.
     *
     * @param registry the Micrometer meter registry to bind counters to
     */
    @Override
    public void bindTo(MeterRegistry registry) {
        // Counter: HTTP responses that failed to write to the channel
        FunctionCounter.builder("netty.http.response.write.failures", bootstrap,
                        b -> b.getHttpRuntimeStats().getHttpResponseWriteFailureCount())
                .description("HTTP response write failures")
                .register(registry);

        // Counter: static file requests rejected due to path traversal or validation
        FunctionCounter.builder("netty.http.static.rejected", bootstrap,
                        b -> b.getHttpRuntimeStats().getStaticFileRejectedCount())
                .description("Static file requests rejected (path traversal, etc.)")
                .register(registry);

        // Counter: static file responses that failed to write to the channel
        FunctionCounter.builder("netty.http.static.write.failures", bootstrap,
                        b -> b.getHttpRuntimeStats().getStaticFileWriteFailureCount())
                .description("Static file write failures")
                .register(registry);

        // Counter: connections closed due to idle timeout
        FunctionCounter.builder("netty.http.idle.closes", bootstrap,
                        b -> b.getHttpRuntimeStats().getIdleCloseCount())
                .description("Idle connection closes")
                .register(registry);

        // Counter: WebSocket upgrade requests rejected at the HTTP handler level
        FunctionCounter.builder("netty.http.websocket.handshake.rejected", bootstrap,
                        b -> b.getHttpRuntimeStats().getWebSocketHandshakeRejectedCount())
                .description("WebSocket handshake rejections at HTTP level")
                .register(registry);

        // Counter: WebSocket requests rejected because the Origin header was not allowed
        FunctionCounter.builder("netty.http.websocket.origin.rejected", bootstrap,
                        b -> b.getHttpRuntimeStats().getWebSocketOriginRejectedCount())
                .description("WebSocket origin check rejections")
                .register(registry);

        // ---- Handler thread pool gauges ----
        Gauge.builder("netty.handler.pool.size", bootstrap,
                        b -> b.getHandlerRuntimeStats().getExecutor().getPoolSize())
                .description("Current handler thread pool size")
                .register(registry);

        Gauge.builder("netty.handler.pool.active", bootstrap,
                        b -> b.getHandlerRuntimeStats().getExecutor().getActiveCount())
                .description("Active handler threads")
                .register(registry);

        Gauge.builder("netty.handler.pool.core", bootstrap,
                        b -> b.getHandlerRuntimeStats().getExecutor().getCorePoolSize())
                .description("Core handler thread pool size")
                .register(registry);

        Gauge.builder("netty.handler.pool.max", bootstrap,
                        b -> b.getHandlerRuntimeStats().getExecutor().getMaximumPoolSize())
                .description("Maximum handler thread pool size")
                .register(registry);

        Gauge.builder("netty.handler.queue.size", bootstrap,
                        b -> b.getHandlerRuntimeStats().getExecutor().getQueueSize())
                .description("Handler work queue depth")
                .register(registry);

        Gauge.builder("netty.handler.queue.remaining", bootstrap,
                        b -> b.getHandlerRuntimeStats().getExecutor().getQueueRemainingCapacity())
                .description("Handler work queue remaining capacity")
                .register(registry);

        Gauge.builder("netty.handler.permits.available", bootstrap,
                        b -> b.getHandlerRuntimeStats().getAvailablePermits())
                .description("Available admission control permits")
                .register(registry);

        Gauge.builder("netty.handler.permits.limit", bootstrap,
                        b -> b.getHandlerRuntimeStats().getPermitLimit())
                .description("Maximum admission control permits")
                .register(registry);

        // ---- Netty pooled allocator memory gauges ----
        Gauge.builder("netty.allocator.used.heap", PooledByteBufAllocator.DEFAULT,
                        alloc -> alloc.metric().usedHeapMemory())
                .baseUnit("bytes")
                .description("Heap memory used by Netty pooled ByteBuf allocator")
                .register(registry);

        Gauge.builder("netty.allocator.used.direct", PooledByteBufAllocator.DEFAULT,
                        alloc -> alloc.metric().usedDirectMemory())
                .baseUnit("bytes")
                .description("Direct memory used by Netty pooled ByteBuf allocator")
                .register(registry);
    }
}
