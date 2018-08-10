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

import com.github.berrywang1996.netty.spring.web.core.ChannelInitializer;
import com.github.berrywang1996.netty.spring.web.util.StartupPropertiesUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelOption;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import lombok.extern.slf4j.Slf4j;

/**
 * @author berrywang1996
 * @version V1.0.0
 */
@Slf4j
public final class NettyServerBootstrap {

    private NioEventLoopGroup bossGroup;

    private NioEventLoopGroup workerGroup;

    private NettyServerStartupProperties startupProperties;

    /**
     * start server.
     */
    public void start(NettyServerStartupProperties startupProperties) throws Exception {

        this.startupProperties = startupProperties;

        // check properties
        StartupPropertiesUtil.checkProperties(startupProperties);

        // start server
        bossGroup = new NioEventLoopGroup();
        workerGroup = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.option(ChannelOption.SO_BACKLOG, 1024);
            b.group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer(this));

            ChannelFuture f = b.bind(startupProperties.getPort()).sync();
            log.info("Netty started on port: {} ", startupProperties.getPort());

            // Wait until the server socket is closed.
            f.channel().closeFuture().sync();

        } finally {
            stop();
        }
    }

    /**
     * Shut down all event loops to terminate all threads.
     */
    public void stop() {
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
}
