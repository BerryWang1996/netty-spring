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
import com.github.berrywang1996.netty.spring.web.context.NettyChannelInitializer;
import com.github.berrywang1996.netty.spring.web.util.DaemonThreadFactory;
import com.github.berrywang1996.netty.spring.web.util.StartupPropertiesUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.context.support.ClassPathXmlApplicationContext;

import java.util.Map;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public final class NettyServerBootstrap {

    public NettyServerBootstrap(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    private NioEventLoopGroup bossGroup;

    private NioEventLoopGroup workerGroup;

    private NettyServerStartupProperties startupProperties;

    private ApplicationContext applicationContext;

    @Getter
    private Map<String, AbstractMappingResolver> webSockeMappingtResolverMap;

    /**
     * start server.
     */
    public void start(NettyServerStartupProperties startupProperties) throws Exception {

        this.startupProperties = startupProperties;

        if (this.applicationContext == null && this.startupProperties.isLoadSpringApplicationContext()) {
            // load spring application context
            log.debug("Loading spring application context.");
            applicationContext = new ClassPathXmlApplicationContext(this.startupProperties.getConfigLocation());
        }

        // check properties
        log.debug("Checking netty server startup properties.");
        StartupPropertiesUtil.checkAndImproveProperties(startupProperties);

        // start server
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();
        ServerBootstrap b = new ServerBootstrap();
        b.option(ChannelOption.SO_BACKLOG, 1024);

        NettyChannelInitializer initializer = new NettyChannelInitializer(this);
        this.webSockeMappingtResolverMap = initializer.getWebSockeMappingtResolverMap();

        b.group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(initializer);

        final ChannelFuture f = b.bind(startupProperties.getPort()).sync();
        log.debug("Netty started on port: {} ", startupProperties.getPort());

        Thread nettyDaemonThread = new DaemonThreadFactory("netty").newThread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Wait until the server socket is closed.
                    f.channel().closeFuture().sync();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                } finally {
                    stop();
                }
            }
        });

        nettyDaemonThread.start();

    }

    /**
     * Shut down all event loops to terminate all threads.
     */
    public void stop() {

        // TODO 优雅退出

        log.info("Netty is shutting down.");
        if (bossGroup.isTerminated() || bossGroup.isShutdown() || bossGroup.isShuttingDown()) {
            return;
        }
        bossGroup.shutdownGracefully();

        if (workerGroup.isTerminated() || workerGroup.isShutdown() || workerGroup.isShuttingDown()) {
            return;
        }
        workerGroup.shutdownGracefully();
    }

    public NettyServerStartupProperties getStartupProperties() {
        return startupProperties;
    }

    public ApplicationContext getApplicationContext() {
        return applicationContext;
    }

}
