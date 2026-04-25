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
        assertEquals(
                4096,
                NettyChannelInitializer.resolveMaxInitialLineLength(new NettyServerStartupProperties().getHttp()));
        assertEquals(
                8192,
                NettyChannelInitializer.resolveMaxHeaderSize(new NettyServerStartupProperties().getHttp()));
        assertEquals(
                8192,
                NettyChannelInitializer.resolveMaxChunkSize(new NettyServerStartupProperties().getHttp()));
        assertEquals(
                0L,
                NettyChannelInitializer.resolveReadTimeoutSeconds(new NettyServerStartupProperties().getHttp()));
        assertEquals(
                0L,
                NettyChannelInitializer.resolveWriteTimeoutSeconds(new NettyServerStartupProperties().getHttp()));
        assertEquals(
                0L,
                NettyChannelInitializer.resolveIdleTimeoutSeconds(new NettyServerStartupProperties().getHttp()));
    }

    @Test
    void resolveMaxHttpContentLengthUsesHttpConfiguration() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getHttp().setMaxContentLength(131072);
        properties.getHttp().setMaxInitialLineLength(2048);
        properties.getHttp().setMaxHeaderSize(4096);
        properties.getHttp().setMaxChunkSize(16384);
        properties.getHttp().setReadTimeoutSeconds(11L);
        properties.getHttp().setWriteTimeoutSeconds(12L);
        properties.getHttp().setIdleTimeoutSeconds(13L);

        assertEquals(131072, NettyChannelInitializer.resolveMaxHttpContentLength(properties.getHttp()));
        assertEquals(2048, NettyChannelInitializer.resolveMaxInitialLineLength(properties.getHttp()));
        assertEquals(4096, NettyChannelInitializer.resolveMaxHeaderSize(properties.getHttp()));
        assertEquals(16384, NettyChannelInitializer.resolveMaxChunkSize(properties.getHttp()));
        assertEquals(11L, NettyChannelInitializer.resolveReadTimeoutSeconds(properties.getHttp()));
        assertEquals(12L, NettyChannelInitializer.resolveWriteTimeoutSeconds(properties.getHttp()));
        assertEquals(13L, NettyChannelInitializer.resolveIdleTimeoutSeconds(properties.getHttp()));
    }

    @Test
    void resolveMaxHttpContentLengthFallsBackWhenConfiguredValueIsInvalid() {
        NettyServerStartupProperties properties = new NettyServerStartupProperties();
        properties.getHttp().setMaxContentLength(0);
        properties.getHttp().setMaxInitialLineLength(0);
        properties.getHttp().setMaxHeaderSize(0);
        properties.getHttp().setMaxChunkSize(0);
        properties.getHttp().setReadTimeoutSeconds(0L);
        properties.getHttp().setWriteTimeoutSeconds(0L);
        properties.getHttp().setIdleTimeoutSeconds(0L);

        assertEquals(65536, NettyChannelInitializer.resolveMaxHttpContentLength(properties.getHttp()));
        assertEquals(4096, NettyChannelInitializer.resolveMaxInitialLineLength(properties.getHttp()));
        assertEquals(8192, NettyChannelInitializer.resolveMaxHeaderSize(properties.getHttp()));
        assertEquals(8192, NettyChannelInitializer.resolveMaxChunkSize(properties.getHttp()));
        assertEquals(0L, NettyChannelInitializer.resolveReadTimeoutSeconds(properties.getHttp()));
        assertEquals(0L, NettyChannelInitializer.resolveWriteTimeoutSeconds(properties.getHttp()));
        assertEquals(0L, NettyChannelInitializer.resolveIdleTimeoutSeconds(properties.getHttp()));
    }
}
