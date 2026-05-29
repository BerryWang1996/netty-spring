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

package com.github.berrywang1996.netty.spring.web.startup;

import com.github.berrywang1996.netty.spring.web.context.AbstractMappingResolver;
import com.github.berrywang1996.netty.spring.web.context.HandlerRuntimeStats;
import com.github.berrywang1996.netty.spring.web.context.HttpRuntimeStats;
import com.github.berrywang1996.netty.spring.web.context.NettyChannelInitializer;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
import com.github.berrywang1996.netty.spring.web.context.WebSocketRuntimeStats;
import com.github.berrywang1996.netty.spring.web.util.DaemonThreadFactory;
import com.github.berrywang1996.netty.spring.web.util.StartupPropertiesUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main server bootstrap responsible for the full lifecycle of the Netty HTTP/WebSocket server.
 *
 * <p>This class manages:
 * <ul>
 *   <li>Server startup: configuring and binding the Netty {@link ServerBootstrap}, initializing
 *       the channel pipeline, and starting the server on the configured port</li>
 *   <li>Graceful shutdown: closing the server channel, shutting down event loop groups,
 *       releasing the mapping supporter resources, and notifying registered stop listeners</li>
 *   <li>Runtime statistics: exposing handler, HTTP, and WebSocket runtime metrics</li>
 *   <li>Spring context management: optionally loading and owning a Spring ApplicationContext</li>
 * </ul>
 *
 * <p>Thread safety: the {@link #stop()} method uses an {@link AtomicBoolean} to ensure
 * it executes at most once, even when called concurrently from multiple threads.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public final class NettyServerBootstrap {

    /** Netty boss event loop group for accepting incoming connections. */
    private NioEventLoopGroup bossGroup;

    /** Netty worker event loop group for handling channel I/O. */
    private NioEventLoopGroup workerGroup;

    private NettyServerStartupProperties startupProperties;

    private ApplicationContext applicationContext;

    /** Flag indicating whether this bootstrap created (and therefore owns) the application context. */
    private boolean ownsApplicationContext;

    /** The server channel bound to the configured port; null before start or after stop. */
    private Channel serverChannel;

    private WebMappingSupporter webMappingSupporter;

    /** Atomic flag ensuring stop() executes at most once, even under concurrent calls. */
    private final AtomicBoolean stopped = new AtomicBoolean(true);

    /** Thread-safe list of callbacks invoked during server shutdown. */
    private final List<Runnable> stopListeners = new CopyOnWriteArrayList<>();

    @Getter
    private Map<String, AbstractMappingResolver> webSocketMappingResolverMap;

    /**
     * Creates a new server bootstrap with an externally provided Spring application context.
     *
     * @param applicationContext the Spring application context (may be {@code null} if context
     *                           should be loaded from the config location specified in startup properties)
     */
    public NettyServerBootstrap(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * Starts the Netty server by initializing the channel pipeline, binding to the configured port,
     * and launching a daemon thread that waits for server shutdown.
     *
     * <p>If no application context was provided at construction time and
     * {@code loadSpringApplicationContext} is enabled, a {@link ClassPathXmlApplicationContext}
     * is loaded from the configured location.
     *
     * <p>On any startup failure, {@link #stop()} is called to release partially initialized resources.
     *
     * @param startupProperties the server startup configuration
     * @throws Exception if binding fails, SSL initialization fails, or controller scanning encounters errors
     */
    public void start(NettyServerStartupProperties startupProperties) throws Exception {
        this.stopped.set(false);
        this.startupProperties = startupProperties;
        try {
            // Optionally load a Spring application context from XML config
            if (this.applicationContext == null && this.startupProperties.isLoadSpringApplicationContext()) {
                log.debug("Loading spring application context.");
                this.applicationContext = new ClassPathXmlApplicationContext(this.startupProperties.getConfigLocation());
                this.ownsApplicationContext = true;
            }

            log.debug("Checking netty server startup properties.");
            StartupPropertiesUtil.checkAndImproveProperties(startupProperties);

            // Create NIO event loop groups for boss (accept) and worker (I/O) threads
            this.bossGroup = new NioEventLoopGroup();
            this.workerGroup = new NioEventLoopGroup();
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.option(ChannelOption.SO_BACKLOG, 1024);

            // Initialize the channel pipeline with all handlers (SSL, codec, compression, routing)
            NettyChannelInitializer initializer = new NettyChannelInitializer(this);
            this.webMappingSupporter = initializer.getSupporter();
            this.webSocketMappingResolverMap = initializer.getWebSocketMappingResolverMap();

            bootstrap.group(this.bossGroup, this.workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(initializer);

            // Bind to the configured port and wait for the bind to complete
            final ChannelFuture bindFuture = bootstrap.bind(startupProperties.getPort()).sync();
            this.serverChannel = bindFuture.channel();
            log.debug("Netty started on port: {} ", startupProperties.getPort());

            // Start a daemon thread that waits for the server channel to close, then triggers shutdown
            Thread nettyDaemonThread = new DaemonThreadFactory("netty").newThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        bindFuture.channel().closeFuture().sync();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Netty close future wait interrupted.", e);
                    } finally {
                        stop();
                    }
                }
            });
            nettyDaemonThread.start();
        } catch (Exception e) {
            // Ensure all partially initialized resources are cleaned up on failure
            stop();
            throw e;
        }
    }

    /**
     * Gracefully shuts down the Netty server and all associated resources.
     *
     * <p>The shutdown sequence (each step is fault-tolerant and logs failures):
     * <ol>
     *   <li>Closes the server channel to stop accepting new connections</li>
     *   <li>Notifies registered stop listeners</li>
     *   <li>Shuts down the web mapping supporter (resolver cleanup and thread pool termination)</li>
     *   <li>Shuts down the boss and worker event loop groups</li>
     *   <li>Closes the owned application context (if this bootstrap created it)</li>
     * </ol>
     *
     * <p>This method is idempotent: concurrent or repeated calls are safely ignored.
     */
    public void stop() {
        if (!this.stopped.compareAndSet(false, true)) {
            return;
        }
        log.info("Netty is shutting down.");
        try {
            runStopAction("closeServerChannel", new Runnable() {
                @Override
                public void run() {
                    closeServerChannel();
                }
            });
            runStopAction("notifyStopListeners", new Runnable() {
                @Override
                public void run() {
                    notifyStopListeners();
                }
            });
            runStopAction("shutdownSupporter", new Runnable() {
                @Override
                public void run() {
                    shutdownSupporter();
                }
            });
            runStopAction("shutdownBossGroup", new Runnable() {
                @Override
                public void run() {
                    shutdownEventLoopGroup(bossGroup);
                }
            });
            runStopAction("shutdownWorkerGroup", new Runnable() {
                @Override
                public void run() {
                    shutdownEventLoopGroup(workerGroup);
                }
            });
            runStopAction("closeOwnedApplicationContext", new Runnable() {
                @Override
                public void run() {
                    closeOwnedApplicationContext();
                }
            });
        } finally {
            clearRuntimeState();
        }
    }

    /** Closes the bound server channel synchronously. Safe to call when the channel is already closed. */
    private void closeServerChannel() {
        if (this.serverChannel == null || !this.serverChannel.isOpen()) {
            return;
        }
        this.serverChannel.close().syncUninterruptibly();
        this.serverChannel = null;
    }

    /** Shuts down the web mapping supporter, releasing its thread pool and resolver resources. */
    private void shutdownSupporter() {
        if (this.webMappingSupporter == null) {
            return;
        }
        this.webMappingSupporter.shutdown();
        this.webMappingSupporter = null;
    }

    /** Gracefully shuts down an event loop group. Safe to call when the group is already terminated. */
    private void shutdownEventLoopGroup(NioEventLoopGroup eventLoopGroup) {
        if (eventLoopGroup == null
                || eventLoopGroup.isTerminated()
                || eventLoopGroup.isShutdown()
                || eventLoopGroup.isShuttingDown()) {
            return;
        }
        eventLoopGroup.shutdownGracefully().syncUninterruptibly();
    }

    /** Closes the application context only if this bootstrap created it (owns it). */
    private void closeOwnedApplicationContext() {
        if (!this.ownsApplicationContext) {
            return;
        }
        try {
            if (this.applicationContext instanceof ConfigurableApplicationContext) {
                ConfigurableApplicationContext context = (ConfigurableApplicationContext) this.applicationContext;
                if (context.isActive()) {
                    context.close();
                }
            }
        } finally {
            this.applicationContext = null;
            this.ownsApplicationContext = false;
        }
    }

    /** Nulls out all mutable fields to aid garbage collection after shutdown. */
    private void clearRuntimeState() {
        this.startupProperties = null;
        this.serverChannel = null;
        this.webMappingSupporter = null;
        this.webSocketMappingResolverMap = null;
        this.bossGroup = null;
        this.workerGroup = null;
    }

    /**
     * Returns the current server startup properties.
     *
     * @return the startup properties, or {@code null} if the server has been stopped
     */
    public NettyServerStartupProperties getStartupProperties() {
        return startupProperties;
    }

    /**
     * Returns the Spring application context used by this server.
     *
     * @return the application context, or {@code null} if not set or already released
     */
    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    /**
     * Registers a callback to be invoked during server shutdown.
     *
     * @param listener the stop listener to register (ignored if {@code null})
     */
    public void addStopListener(Runnable listener) {
        if (listener == null) {
            return;
        }
        this.stopListeners.add(listener);
    }

    /**
     * Returns a snapshot of handler thread pool and semaphore runtime statistics.
     *
     * @return current {@link HandlerRuntimeStats}, or an empty instance if the server is not running
     */
    public HandlerRuntimeStats getHandlerRuntimeStats() {
        if (this.webMappingSupporter == null) {
            return HandlerRuntimeStats.empty();
        }
        return this.webMappingSupporter.getRuntimeStats();
    }

    /**
     * Returns a snapshot of HTTP-level runtime statistics (write failures, rejections, idle closes).
     *
     * @return current {@link HttpRuntimeStats}, or an empty instance if the server is not running
     */
    public HttpRuntimeStats getHttpRuntimeStats() {
        if (this.webMappingSupporter == null) {
            return HttpRuntimeStats.empty();
        }
        return this.webMappingSupporter.getHttpRuntimeStats();
    }

    /**
     * Returns a snapshot of WebSocket runtime statistics (session counts, event counters).
     *
     * @return current {@link WebSocketRuntimeStats}, or an empty instance if the server is not running
     */
    public WebSocketRuntimeStats getWebSocketRuntimeStats() {
        if (this.webMappingSupporter == null) {
            return WebSocketRuntimeStats.empty();
        }
        return this.webMappingSupporter.getWebSocketRuntimeStats();
    }

    /**
     * Returns whether the Netty server is currently running (started but not yet stopped).
     *
     * @return {@code true} if the server has been started and not yet stopped
     */
    public boolean isRunning() {
        return !stopped.get();
    }

    /**
     * Returns the port number the server is bound to, or {@code -1} if the server
     * has not been started or the startup properties are not available.
     *
     * @return the server port, or {@code -1}
     */
    public int getPort() {
        if (startupProperties == null) {
            return -1;
        }
        Integer port = startupProperties.getPort();
        return port == null ? -1 : port;
    }

    /** Invokes all registered stop listeners, logging but not propagating individual failures. */
    private void notifyStopListeners() {
        for (Runnable listener : this.stopListeners) {
            try {
                listener.run();
            } catch (Exception e) {
                log.warn("Invoke stop listener failed.", e);
            }
        }
    }

    /**
     * Executes a named shutdown action, catching and logging any runtime exceptions
     * to ensure the shutdown sequence continues even if individual steps fail.
     */
    private void runStopAction(String actionName, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.warn("Stop action {} failed.", actionName, e);
        }
    }

}
