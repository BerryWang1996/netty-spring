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
 * SPI for propagating a W3C {@code traceparent} across cluster nodes so a distributed trace
 * correlates in logs on the receiving node. Tracer-agnostic: the default
 * {@code MdcClusterTraceContext} is MDC-based and zero-dependency; integrators can supply a
 * bean that reads/writes their tracer (Sleuth/Brave) directly.
 *
 * <p>Wired only when {@code server.netty.websocket.cluster.trace-propagation.enable=true}.
 *
 * @author berrywang1996
 * @since V1.9.0
 */
public interface ClusterTraceContext {

    /** The current thread's W3C traceparent (e.g. from MDC / the active span), or null if none. */
    String currentTraceparent();

    /**
     * Restore a traceparent into the ambient context (e.g. MDC) for a cross-node delivery.
     * Returns a {@link Scope} that reverts the restoration on close. A null/blank/malformed
     * value yields {@link #NOOP}.
     */
    Scope restore(String traceparent);

    /** Closeable that reverts what {@link #restore} set. {@code close()} never throws. */
    interface Scope extends AutoCloseable {
        @Override
        void close();
    }

    /** No-op scope (trace propagation disabled or nothing to restore). */
    Scope NOOP = () -> { };
}
