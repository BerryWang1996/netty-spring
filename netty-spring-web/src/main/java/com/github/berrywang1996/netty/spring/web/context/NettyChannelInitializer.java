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

package com.github.berrywang1996.netty.spring.web.context;

import com.github.berrywang1996.netty.spring.web.handler.CompressorHandler;
import com.github.berrywang1996.netty.spring.web.handler.ServiceHandler;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerBootstrap;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.handler.timeout.IdleStateHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class NettyChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final int DEFAULT_MAX_INITIAL_LINE_LENGTH = 4096;

    private static final int DEFAULT_MAX_HEADER_SIZE = 8192;

    private static final int DEFAULT_MAX_CHUNK_SIZE = 8192;

    private static final int DEFAULT_MAX_HTTP_CONTENT_LENGTH = 65536;

    private final NettyServerBootstrap nettyServerBootstrap;

    @Getter
    private final WebMappingSupporter supporter;

    private SslContext sslCtx = null;

    @Getter
    private final Map<String, AbstractMappingResolver> webSockeMappingtResolverMap;

    public NettyChannelInitializer(NettyServerBootstrap nettyServerBootstrap) throws Exception {

        this.nettyServerBootstrap = nettyServerBootstrap;
        NettyServerStartupProperties.Http httpProperties = nettyServerBootstrap.getStartupProperties().getHttp();

        // Configure SSL
        if (httpProperties.getSsl() != null && httpProperties.getSsl().isEnable()) {
            File certificateFile = new File(httpProperties.getSsl().getCertificate());
            File privateKeyFile = new File(httpProperties.getSsl().getCertificateKey());
            SslContextBuilder sslContextBuilder = SslContextBuilder.forServer(certificateFile, privateKeyFile);
            configureSslContextBuilder(sslContextBuilder, httpProperties.getSsl());
            sslCtx = sslContextBuilder.build();
            log.debug("Enable ssl, certificate file:{}, private key file:{}",
                    certificateFile.getPath(),
                    privateKeyFile.getCanonicalPath());
        }

        // Runtime supporter
        log.debug("Init web mapping supporter.");
        supporter = new WebMappingSupporter(
                nettyServerBootstrap.getStartupProperties(),
                nettyServerBootstrap.getApplicationContext());

        this.webSockeMappingtResolverMap = supporter.getWebSocketMappingtResolverMap();
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        NettyServerStartupProperties.Http httpProperties = nettyServerBootstrap.getStartupProperties().getHttp();
        if (sslCtx != null) {
            p.addLast(sslCtx.newHandler(ch.alloc()));
        }
        addTimeoutHandlers(p, httpProperties);
        p.addLast(new HttpServerCodec(
                resolveMaxInitialLineLength(httpProperties),
                resolveMaxHeaderSize(httpProperties),
                resolveMaxChunkSize(httpProperties)));
        if (httpProperties.getGzip().isEnable()) {
            p.addLast(new CompressorHandler(
                    httpProperties.getGzip().getCompressionLevel(),
                    httpProperties.getGzip().getWindowBits(),
                    httpProperties.getGzip().getMemLevel(),
                    httpProperties.getGzip().getContentSizeThreshold(),
                    httpProperties.getGzip().getTypes())
            );
        }
        p.addLast(new HttpObjectAggregator(resolveMaxHttpContentLength(httpProperties)));
        p.addLast(new ChunkedWriteHandler());
        p.addLast(new ServiceHandler(supporter));
    }

    private static void addTimeoutHandlers(ChannelPipeline pipeline, NettyServerStartupProperties.Http httpProperties) {
        long readTimeoutSeconds = resolveReadTimeoutSeconds(httpProperties);
        if (readTimeoutSeconds > 0L) {
            pipeline.addLast(new ReadTimeoutHandler(readTimeoutSeconds, TimeUnit.SECONDS));
        }
        long writeTimeoutSeconds = resolveWriteTimeoutSeconds(httpProperties);
        if (writeTimeoutSeconds > 0L) {
            pipeline.addLast(new WriteTimeoutHandler(writeTimeoutSeconds, TimeUnit.SECONDS));
        }
        long idleTimeoutSeconds = resolveIdleTimeoutSeconds(httpProperties);
        if (idleTimeoutSeconds > 0L) {
            pipeline.addLast(new IdleStateHandler(0L, 0L, idleTimeoutSeconds, TimeUnit.SECONDS));
        }
    }

    static int resolveMaxHttpContentLength(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getMaxContentLength() <= 0) {
            return DEFAULT_MAX_HTTP_CONTENT_LENGTH;
        }
        return httpProperties.getMaxContentLength();
    }

    static int resolveMaxInitialLineLength(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getMaxInitialLineLength() <= 0) {
            return DEFAULT_MAX_INITIAL_LINE_LENGTH;
        }
        return httpProperties.getMaxInitialLineLength();
    }

    static int resolveMaxHeaderSize(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getMaxHeaderSize() <= 0) {
            return DEFAULT_MAX_HEADER_SIZE;
        }
        return httpProperties.getMaxHeaderSize();
    }

    static int resolveMaxChunkSize(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getMaxChunkSize() <= 0) {
            return DEFAULT_MAX_CHUNK_SIZE;
        }
        return httpProperties.getMaxChunkSize();
    }

    static long resolveReadTimeoutSeconds(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getReadTimeoutSeconds() <= 0L) {
            return 0L;
        }
        return httpProperties.getReadTimeoutSeconds();
    }

    static long resolveWriteTimeoutSeconds(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getWriteTimeoutSeconds() <= 0L) {
            return 0L;
        }
        return httpProperties.getWriteTimeoutSeconds();
    }

    static long resolveIdleTimeoutSeconds(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getIdleTimeoutSeconds() <= 0L) {
            return 0L;
        }
        return httpProperties.getIdleTimeoutSeconds();
    }

    static void configureSslContextBuilder(SslContextBuilder sslContextBuilder,
                                           NettyServerStartupProperties.Ssl sslProperties) {
        if (sslContextBuilder == null || sslProperties == null) {
            return;
        }
        List<String> protocols = resolveDelimitedConfig(sslProperties.getProtocols());
        if (!protocols.isEmpty()) {
            sslContextBuilder.protocols(protocols.toArray(new String[0]));
        }
        List<String> ciphers = resolveDelimitedConfig(sslProperties.getCiphers());
        if (!ciphers.isEmpty()) {
            sslContextBuilder.ciphers(ciphers);
        }
    }

    static List<String> resolveDelimitedConfig(String configValue) {
        if (configValue == null || configValue.trim().isEmpty()) {
            return Collections.emptyList();
        }
        String[] tokens = configValue.trim().split("[,\\s]+");
        List<String> values = new ArrayList<>(tokens.length);
        for (String token : tokens) {
            if (token != null && !token.trim().isEmpty()) {
                values.add(token.trim());
            }
        }
        return values;
    }

}
