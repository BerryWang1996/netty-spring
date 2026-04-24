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
import com.github.berrywang1996.netty.spring.web.context.NettyChannelInitializer;
import com.github.berrywang1996.netty.spring.web.context.WebMappingSupporter;
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

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public final class NettyServerBootstrap {

    private NioEventLoopGroup bossGroup;

    private NioEventLoopGroup workerGroup;

    private NettyServerStartupProperties startupProperties;

    private ApplicationContext applicationContext;

    private boolean ownsApplicationContext;

    private Channel serverChannel;

    private WebMappingSupporter webMappingSupporter;

    private final AtomicBoolean stopped = new AtomicBoolean(true);

    @Getter
    private Map<String, AbstractMappingResolver> webSockeMappingtResolverMap;

    public NettyServerBootstrap(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    /**
     * start server.
     */
    public void start(NettyServerStartupProperties startupProperties) throws Exception {
        this.stopped.set(false);
        this.startupProperties = startupProperties;
        try {
            if (this.applicationContext == null && this.startupProperties.isLoadSpringApplicationContext()) {
                log.debug("Loading spring application context.");
                this.applicationContext = new ClassPathXmlApplicationContext(this.startupProperties.getConfigLocation());
                this.ownsApplicationContext = true;
            }

            log.debug("Checking netty server startup properties.");
            StartupPropertiesUtil.checkAndImproveProperties(startupProperties);

            this.bossGroup = new NioEventLoopGroup();
            this.workerGroup = new NioEventLoopGroup();
            ServerBootstrap bootstrap = new ServerBootstrap();
            bootstrap.option(ChannelOption.SO_BACKLOG, 1024);

            NettyChannelInitializer initializer = new NettyChannelInitializer(this);
            this.webMappingSupporter = initializer.getSupporter();
            this.webSockeMappingtResolverMap = initializer.getWebSockeMappingtResolverMap();

            bootstrap.group(this.bossGroup, this.workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(initializer);

            final ChannelFuture bindFuture = bootstrap.bind(startupProperties.getPort()).sync();
            this.serverChannel = bindFuture.channel();
            log.debug("Netty started on port: {} ", startupProperties.getPort());

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
            stop();
            throw e;
        }
    }

    /**
     * Shut down all event loops to terminate all threads.
     */
    public void stop() {
        if (!this.stopped.compareAndSet(false, true)) {
            return;
        }
        log.info("Netty is shutting down.");
        closeServerChannel();
        shutdownSupporter();
        shutdownEventLoopGroup(this.bossGroup);
        shutdownEventLoopGroup(this.workerGroup);
        closeOwnedApplicationContext();
    }

    private void closeServerChannel() {
        if (this.serverChannel == null || !this.serverChannel.isOpen()) {
            return;
        }
        this.serverChannel.close().syncUninterruptibly();
        this.serverChannel = null;
    }

    private void shutdownSupporter() {
        if (this.webMappingSupporter == null) {
            return;
        }
        this.webMappingSupporter.shutdown();
        this.webMappingSupporter = null;
    }

    private void shutdownEventLoopGroup(NioEventLoopGroup eventLoopGroup) {
        if (eventLoopGroup == null
                || eventLoopGroup.isTerminated()
                || eventLoopGroup.isShutdown()
                || eventLoopGroup.isShuttingDown()) {
            return;
        }
        eventLoopGroup.shutdownGracefully().syncUninterruptibly();
    }

    private void closeOwnedApplicationContext() {
        if (!this.ownsApplicationContext || !(this.applicationContext instanceof ConfigurableApplicationContext)) {
            return;
        }
        ConfigurableApplicationContext context = (ConfigurableApplicationContext) this.applicationContext;
        if (context.isActive()) {
            context.close();
        }
        this.ownsApplicationContext = false;
    }

    public NettyServerStartupProperties getStartupProperties() {
        return startupProperties;
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

    public HandlerRuntimeStats getHandlerRuntimeStats() {
        if (this.webMappingSupporter == null) {
            return HandlerRuntimeStats.empty();
        }
        return this.webMappingSupporter.getRuntimeStats();
    }

}
