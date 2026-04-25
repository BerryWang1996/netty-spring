package com.github.berrywang1996.netty.spring.web.context;

import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NettyChannelInitializerTest {

    @Test
    void resolveMaxHttpContentLengthUsesDefaultWhenNotConfigured() {
        assertEquals(
                65536,
                NettyChannelInitializer.resolveMaxHttpContentLength(new NettyServerStartupProperties().getHttp()));
    }

    @Test
    void resolveMaxHttpContentLengthUsesHttpConfiguration() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getHttp().setMaxContentLength(131072);

        assertEquals(131072, NettyChannelInitializer.resolveMaxHttpContentLength(properties.getHttp()));
    }

    @Test
    void resolveMaxHttpContentLengthFallsBackWhenConfiguredValueIsInvalid() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getHttp().setMaxContentLength(0);

        assertEquals(65536, NettyChannelInitializer.resolveMaxHttpContentLength(properties.getHttp()));
    }
}
