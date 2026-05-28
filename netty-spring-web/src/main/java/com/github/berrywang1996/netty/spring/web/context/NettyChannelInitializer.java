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
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.flush.FlushConsolidationHandler;
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
 * Netty channel pipeline initializer that configures the full HTTP processing pipeline
 * for each newly accepted {@link SocketChannel}.
 *
 * <p>This initializer builds the channel pipeline with the following stages (in order):
 * <ol>
 *   <li>Optional SSL/TLS handler (if SSL is enabled)</li>
 *   <li>Timeout handlers (read, write, and idle timeouts)</li>
 *   <li>HTTP codec for request/response encoding and decoding</li>
 *   <li>Optional GZIP compression handler</li>
 *   <li>HTTP object aggregator to assemble full HTTP messages</li>
 *   <li>Chunked write handler for large file transfers</li>
 *   <li>{@link ServiceHandler} for MVC/WebSocket/static-file request dispatching</li>
 * </ol>
 *
 * <p>It also initializes the {@link WebMappingSupporter} which scans Spring controllers
 * and registers URL mapping resolvers used for request routing.
 *
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

    /** SSL context for TLS encryption; null if SSL is not enabled. */
    private SslContext sslCtx = null;

    @Getter
    private final Map<String, AbstractMappingResolver> webSocketMappingResolverMap;

    /**
     * Creates a new channel initializer, configuring SSL if enabled and initializing
     * the web mapping supporter that scans for controller mappings.
     *
     * @param nettyServerBootstrap the server bootstrap providing startup properties and application context
     * @throws Exception if SSL context building or mapping supporter initialization fails
     */
    public NettyChannelInitializer(NettyServerBootstrap nettyServerBootstrap) throws Exception {

        this.nettyServerBootstrap = nettyServerBootstrap;
        NettyServerStartupProperties.Http httpProperties = nettyServerBootstrap.getStartupProperties().getHttp();

        // Configure SSL if enabled in properties
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

        // Initialize the web mapping supporter that scans Spring controllers and registers URL mappings
        log.debug("Init web mapping supporter.");
        supporter = new WebMappingSupporter(
                nettyServerBootstrap.getStartupProperties(),
                nettyServerBootstrap.getApplicationContext());

        this.webSocketMappingResolverMap = supporter.getWebSocketMappingResolverMap();
    }

    /**
     * Initializes the channel pipeline for a newly accepted socket connection.
     *
     * <p>Adds handlers in order: SSL (optional), timeouts, HTTP codec, GZIP compression
     * (optional), HTTP aggregation, chunked write support, and the service handler.
     *
     * @param ch the newly accepted socket channel
     */
    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline p = ch.pipeline();
        NettyServerStartupProperties properties = nettyServerBootstrap.getStartupProperties();
        NettyServerStartupProperties.Http httpProperties = properties.getHttp();
        NettyServerStartupProperties.WebSocket wsProperties = properties.getWebSocket();

        // Configure per-channel write buffer water marks for backpressure
        configureWriteBufferWaterMark(ch, wsProperties);

        // Add SSL handler first if TLS is configured
        if (sslCtx != null) {
            p.addLast(sslCtx.newHandler(ch.alloc()));
        }

        // Add read/write/idle timeout handlers
        addTimeoutHandlers(p, httpProperties);

        // HTTP codec with configurable limits for initial line, headers, and chunk sizes
        p.addLast(new HttpServerCodec(
                resolveMaxInitialLineLength(httpProperties),
                resolveMaxHeaderSize(httpProperties),
                resolveMaxChunkSize(httpProperties)));

        // Optional GZIP compression for configured content types
        if (httpProperties.getGzip().isEnable()) {
            p.addLast(new CompressorHandler(
                    httpProperties.getGzip().getCompressionLevel(),
                    httpProperties.getGzip().getWindowBits(),
                    httpProperties.getGzip().getMemLevel(),
                    httpProperties.getGzip().getContentSizeThreshold(),
                    httpProperties.getGzip().getTypes())
            );
        }

        // Aggregate HTTP chunks into a single FullHttpRequest
        p.addLast(new HttpObjectAggregator(resolveMaxHttpContentLength(httpProperties)));
        // Support chunked transfer encoding for large file responses
        p.addLast(new ChunkedWriteHandler());

        // Flush consolidation: coalesce multiple flush() calls into fewer syscalls.
        // The consolidateWhenNoReadInProgress=true flag is critical for broadcast
        // scenarios where flushes occur outside of channelRead cycles.
        addFlushConsolidationHandler(p, wsProperties);

        // Core request dispatcher that routes to MVC controllers, WebSocket handlers, or static files
        p.addLast(new ServiceHandler(supporter));
    }

    /**
     * Configures the per-channel {@link WriteBufferWaterMark} for backpressure control.
     *
     * <p>When the number of bytes queued for write exceeds the high water mark, the channel
     * becomes non-writable ({@code channel.isWritable()} returns {@code false}). Once queued
     * bytes drop below the low water mark, the channel becomes writable again. This provides
     * per-session backpressure during broadcast, preventing fast producers from overwhelming
     * slow consumers.
     *
     * @param ch           the socket channel to configure
     * @param wsProperties WebSocket properties containing water mark values (may be {@code null})
     */
    private static void configureWriteBufferWaterMark(SocketChannel ch,
                                                      NettyServerStartupProperties.WebSocket wsProperties) {
        if (wsProperties == null) {
            return;
        }
        int low = wsProperties.getWriteBufferLowWaterMark();
        int high = wsProperties.getWriteBufferHighWaterMark();
        if (low > 0 && high > 0 && high > low) {
            ch.config().setWriteBufferWaterMark(new WriteBufferWaterMark(low, high));
        } else if (low != 0 || high != 0) {
            // Non-default values were provided but fail validation — warn the user
            log.warn("Invalid WriteBufferWaterMark configuration (low={}, high={}). "
                    + "Requires: low > 0, high > 0, high > low. Using Netty defaults.", low, high);
        }
    }

    /**
     * Adds a {@link FlushConsolidationHandler} to the pipeline when the configured
     * threshold is positive. This handler coalesces multiple {@code flush()} calls
     * into fewer system calls, significantly reducing the per-message overhead during
     * high-throughput broadcast.
     *
     * <p>The {@code consolidateWhenNoReadInProgress=true} parameter ensures flush
     * consolidation also applies to writes that happen outside of a {@code channelRead}
     * cycle, which is the typical case for broadcast (writes originate from application
     * code or EventLoop tasks, not from inbound frame processing).
     *
     * @param pipeline     the channel pipeline to add the handler to
     * @param wsProperties WebSocket properties containing the consolidation threshold (may be {@code null})
     */
    private static void addFlushConsolidationHandler(ChannelPipeline pipeline,
                                                     NettyServerStartupProperties.WebSocket wsProperties) {
        if (wsProperties == null) {
            return;
        }
        int threshold = wsProperties.getFlushConsolidationThreshold();
        if (threshold > 0) {
            pipeline.addLast(new FlushConsolidationHandler(threshold, true));
        }
    }

    /**
     * Adds read, write, and idle timeout handlers to the pipeline when the respective
     * timeout values are configured to be greater than zero.
     *
     * @param pipeline       the channel pipeline to add handlers to
     * @param httpProperties the HTTP properties containing timeout configuration
     */
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

    /**
     * Resolves the maximum HTTP content length, falling back to the default if not configured.
     *
     * @param httpProperties the HTTP properties to read from
     * @return the maximum HTTP content length in bytes
     */
    static int resolveMaxHttpContentLength(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getMaxContentLength() <= 0) {
            return DEFAULT_MAX_HTTP_CONTENT_LENGTH;
        }
        return httpProperties.getMaxContentLength();
    }

    /**
     * Resolves the maximum HTTP initial line length, falling back to the default if not configured.
     *
     * @param httpProperties the HTTP properties to read from
     * @return the maximum initial line length in bytes
     */
    static int resolveMaxInitialLineLength(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getMaxInitialLineLength() <= 0) {
            return DEFAULT_MAX_INITIAL_LINE_LENGTH;
        }
        return httpProperties.getMaxInitialLineLength();
    }

    /**
     * Resolves the maximum HTTP header size, falling back to the default if not configured.
     *
     * @param httpProperties the HTTP properties to read from
     * @return the maximum header size in bytes
     */
    static int resolveMaxHeaderSize(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getMaxHeaderSize() <= 0) {
            return DEFAULT_MAX_HEADER_SIZE;
        }
        return httpProperties.getMaxHeaderSize();
    }

    /**
     * Resolves the maximum HTTP chunk size, falling back to the default if not configured.
     *
     * @param httpProperties the HTTP properties to read from
     * @return the maximum chunk size in bytes
     */
    static int resolveMaxChunkSize(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getMaxChunkSize() <= 0) {
            return DEFAULT_MAX_CHUNK_SIZE;
        }
        return httpProperties.getMaxChunkSize();
    }

    /**
     * Resolves the read timeout in seconds. Returns 0 (disabled) if not configured.
     *
     * @param httpProperties the HTTP properties to read from
     * @return the read timeout in seconds, or 0 if disabled
     */
    static long resolveReadTimeoutSeconds(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getReadTimeoutSeconds() <= 0L) {
            return 0L;
        }
        return httpProperties.getReadTimeoutSeconds();
    }

    /**
     * Resolves the write timeout in seconds. Returns 0 (disabled) if not configured.
     *
     * @param httpProperties the HTTP properties to read from
     * @return the write timeout in seconds, or 0 if disabled
     */
    static long resolveWriteTimeoutSeconds(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getWriteTimeoutSeconds() <= 0L) {
            return 0L;
        }
        return httpProperties.getWriteTimeoutSeconds();
    }

    /**
     * Resolves the idle timeout in seconds. Returns 0 (disabled) if not configured.
     *
     * @param httpProperties the HTTP properties to read from
     * @return the idle timeout in seconds, or 0 if disabled
     */
    static long resolveIdleTimeoutSeconds(NettyServerStartupProperties.Http httpProperties) {
        if (httpProperties == null || httpProperties.getIdleTimeoutSeconds() <= 0L) {
            return 0L;
        }
        return httpProperties.getIdleTimeoutSeconds();
    }

    /**
     * Configures the SSL context builder with protocol and cipher settings from properties.
     *
     * @param sslContextBuilder the SSL context builder to configure
     * @param sslProperties     the SSL properties containing protocol and cipher configuration
     */
    static void configureSslContextBuilder(SslContextBuilder sslContextBuilder,
                                           NettyServerStartupProperties.Ssl sslProperties) {
        if (sslContextBuilder == null || sslProperties == null) {
            return;
        }
        List<String> protocols = resolveDelimitedConfig(sslProperties.getProtocols());
        if (!protocols.isEmpty()) {
            sslContextBuilder.protocols(protocols.toArray(new String[0]));
        } else {
            // Default to TLS 1.2+ when no protocols are explicitly configured.
            // TLS 1.0 and 1.1 have known vulnerabilities (BEAST, POODLE) and are
            // deprecated by RFC 8996.
            sslContextBuilder.protocols("TLSv1.2", "TLSv1.3");
        }
        List<String> ciphers = resolveDelimitedConfig(sslProperties.getCiphers());
        if (!ciphers.isEmpty()) {
            sslContextBuilder.ciphers(ciphers);
        }
    }

    /**
     * Parses a comma-or-whitespace-delimited configuration string into a list of trimmed values.
     *
     * @param configValue the delimited string to parse (may be {@code null} or empty)
     * @return an unmodifiable list of parsed values, or an empty list if the input is blank
     */
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
