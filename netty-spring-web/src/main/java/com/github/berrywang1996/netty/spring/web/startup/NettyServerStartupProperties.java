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

/**
 * Configuration properties for the Netty HTTP/WebSocket server.
 *
 * <p>Aggregates all server startup settings into a single POJO, including:
 * <ul>
 *   <li>General settings: application name, port, Spring config location</li>
 *   <li>HTTP settings: codec limits, timeouts, static file serving, SSL, and GZIP</li>
 *   <li>MVC settings: enable/disable MVC controller mapping</li>
 *   <li>WebSocket settings: thread pool sizes, connection limits, heartbeat, crypto, and broadcast policies</li>
 *   <li>Management settings: lightweight health and status endpoint paths</li>
 * </ul>
 *
 * <p>Properties are typically populated from an external configuration source (e.g. Spring property binding)
 * and passed to {@link NettyServerBootstrap#start(NettyServerStartupProperties)} to start the server.
 *
 * @author berrywang1996
 * @since V1.0.0
 */
public class NettyServerStartupProperties {

    /**
     * Default application name.
     */
    private String applicationName = "_netty_spring_application";

    /**
     * Loading spring application context before start netty server.
     */
    private boolean loadSpringApplicationContext = false;

    /**
     * Netty server port.
     */
    private Integer port = 8080;

    /**
     * Spring configuration file Location.
     */
    private String configLocation = "classpath:applicationContext.xml";

    /**
     * Handle file if true
     */
    private boolean handleFile = false;

    /**
     * Netty Server root file location. Root directory under the current project.
     */
    private String fileLocation;

    /**
     * Netty Server info file location. Root directory under the current project.
     */
    private String infoLocation;

    /**
     * Netty server ssl configure.
     */
    private Ssl ssl = new Ssl();

    /**
     * Netty server gzip configure.
     */
    private Gzip gzip = new Gzip();

    /**
     * Netty server http configure. This is the preferred namespace for HTTP/file/gzip/ssl related settings.
     */
    private final Http http = new Http();

    /**
     * Netty server mvc configure.
     */
    private Mvc mvc = new Mvc();

    /**
     * Netty server websocket configure.
     */
    private WebSocket webSocket = new WebSocket();

    /**
     * Netty server lightweight management endpoints.
     */
    private Management management = new Management();

    public String getApplicationName() {
        return applicationName;
    }

    public void setApplicationName(String applicationName) {
        this.applicationName = applicationName;
    }

    public boolean isLoadSpringApplicationContext() {
        return loadSpringApplicationContext;
    }

    public void setLoadSpringApplicationContext(boolean loadSpringApplicationContext) {
        this.loadSpringApplicationContext = loadSpringApplicationContext;
    }

    public Integer getPort() {
        return port;
    }

    public void setPort(Integer port) {
        this.port = port;
    }

    public String getConfigLocation() {
        return configLocation;
    }

    public void setConfigLocation(String configLocation) {
        this.configLocation = configLocation;
    }

    public boolean isHandleFile() {
        return handleFile;
    }

    public void setHandleFile(boolean handleFile) {
        this.handleFile = handleFile;
    }

    public String getFileLocation() {
        return fileLocation;
    }

    public void setFileLocation(String fileLocation) {
        this.fileLocation = fileLocation;
    }

    public String getInfoLocation() {
        return infoLocation;
    }

    public void setInfoLocation(String infoLocation) {
        this.infoLocation = infoLocation;
    }

    public Ssl getSsl() {
        return ssl;
    }

    public void setSsl(Ssl ssl) {
        this.ssl = ssl;
    }

    public Gzip getGzip() {
        return gzip;
    }

    public void setGzip(Gzip gzip) {
        this.gzip = gzip;
    }

    public Http getHttp() {
        return http;
    }

    public Mvc getMvc() {
        return mvc;
    }

    public void setMvc(Mvc mvc) {
        this.mvc = mvc;
    }

    public WebSocket getWebSocket() {
        return webSocket;
    }

    public void setWebSocket(WebSocket webSocket) {
        this.webSocket = webSocket;
    }

    public Management getManagement() {
        return management;
    }

    public void setManagement(Management management) {
        this.management = management;
    }

    /**
     * SSL/TLS configuration for securing the Netty server with HTTPS.
     */
    public static class Ssl {

        /**
         * Enable ssl.
         */
        private boolean enable = false;

        /**
         * An X.509 certificate chain file in PEM format.
         */
        private String certificate;

        /**
         * A PKCS#8 private key file in PEM format.
         */
        private String certificateKey;

        /**
         * Enabled TLS protocols, separated by comma or whitespace. Empty means Netty/JDK defaults.
         */
        private String protocols;

        /**
         * Enabled TLS cipher suites, separated by comma or whitespace. Empty means Netty/JDK defaults.
         */
        private String ciphers;

        public boolean isEnable() {
            return enable;
        }

        public void setEnable(boolean enable) {
            this.enable = enable;
        }

        public String getCertificate() {
            return certificate;
        }

        public void setCertificate(String certificate) {
            this.certificate = certificate;
        }

        public String getCertificateKey() {
            return certificateKey;
        }

        public void setCertificateKey(String certificateKey) {
            this.certificateKey = certificateKey;
        }

        public String getProtocols() {
            return protocols;
        }

        public void setProtocols(String protocols) {
            this.protocols = protocols;
        }

        public String getCiphers() {
            return ciphers;
        }

        public void setCiphers(String ciphers) {
            this.ciphers = ciphers;
        }
    }

    /**
     * GZIP compression configuration for HTTP responses.
     */
    public static class Gzip {

        /**
         * Enable gzip.
         */
        private boolean enable = false;

        /**
         * Gzip types.
         */
        private String types = "text/html text/plain application/javascript application/x-javascript text/javascript " +
                "text/css application/xml image/jpeg image/gif image/png";

        /**
         * 1 yields the fastest compression and 9 yields the best compression. 0 means no compression. The default
         * compression level is 6.
         */
        private int compressionLevel = 6;

        /**
         * The base two logarithm of the size of the history buffer. The value should be in the range 9 to 15
         * inclusive. Larger values result in better compression at the expense of memory usage. The default value is
         * 15.
         */
        private int windowBits = 15;

        /**
         * How much memory should be allocated for the internal compression state. 1 uses minimum memory and 9 uses
         * maximum memory. Larger values result in better and faster compression at the expense of memory usage. The
         * default value is 8.
         */
        private int memLevel = 8;

        /**
         * The response body is compressed when the size of the response body exceeds the threshold. The value should
         * be a non negative number. 0 will enable compression for all responses.
         */
        private int contentSizeThreshold = 0;

        public boolean isEnable() {
            return enable;
        }

        public void setEnable(boolean enable) {
            this.enable = enable;
        }

        public String getTypes() {
            return types;
        }

        public void setTypes(String types) {
            this.types = types;
        }

        public int getCompressionLevel() {
            return compressionLevel;
        }

        public void setCompressionLevel(int compressionLevel) {
            this.compressionLevel = compressionLevel;
        }

        public int getWindowBits() {
            return windowBits;
        }

        public void setWindowBits(int windowBits) {
            this.windowBits = windowBits;
        }

        public int getMemLevel() {
            return memLevel;
        }

        public void setMemLevel(int memLevel) {
            this.memLevel = memLevel;
        }

        public int getContentSizeThreshold() {
            return contentSizeThreshold;
        }

        public void setContentSizeThreshold(int contentSizeThreshold) {
            this.contentSizeThreshold = contentSizeThreshold;
        }
    }

    /**
     * MVC controller mapping configuration.
     */
    public static class Mvc {

        /**
         * Enable mvc mapping.
         */
        private boolean enable = true;

        public boolean isEnable() {
            return enable;
        }

        public void setEnable(boolean enable) {
            this.enable = enable;
        }
    }

    /**
     * Lightweight management endpoint configuration for health checks and runtime status.
     */
    public static class Management {

        /**
         * Enable built-in management endpoints.
         */
        private boolean enable = false;

        /**
         * Lightweight health endpoint path.
         */
        private String healthPath = "/netty/health";

        /**
         * Runtime status endpoint path.
         */
        private String statusPath = "/netty/status";

        public boolean isEnable() {
            return enable;
        }

        public void setEnable(boolean enable) {
            this.enable = enable;
        }

        public String getHealthPath() {
            return healthPath;
        }

        public void setHealthPath(String healthPath) {
            this.healthPath = healthPath;
        }

        public String getStatusPath() {
            return statusPath;
        }

        public void setStatusPath(String statusPath) {
            this.statusPath = statusPath;
        }
    }

    /**
     * HTTP-specific configuration including codec limits, timeouts, and delegation to
     * the parent class for file-serving, SSL, and GZIP settings.
     *
     * <p>This is a non-static inner class so it can delegate file/SSL/GZIP getters/setters
     * to the enclosing {@link NettyServerStartupProperties} for backward compatibility.
     */
    public class Http {

        /**
         * Max HTTP initial line length in bytes.
         */
        private int maxInitialLineLength = 4096;

        /**
         * Max HTTP header size in bytes.
         */
        private int maxHeaderSize = 8192;

        /**
         * Max HTTP chunk size in bytes.
         */
        private int maxChunkSize = 8192;

        /**
         * Max aggregated HTTP content length in bytes.
         */
        private int maxContentLength = 65536;

        /**
         * Read timeout in seconds. 0 means disabled.
         */
        private long readTimeoutSeconds = 0L;

        /**
         * Write timeout in seconds. 0 means disabled.
         */
        private long writeTimeoutSeconds = 0L;

        /**
         * All-idle timeout in seconds. 0 means disabled.
         */
        private long idleTimeoutSeconds = 0L;

        public boolean isHandleFile() {
            return NettyServerStartupProperties.this.handleFile;
        }

        public void setHandleFile(boolean handleFile) {
            NettyServerStartupProperties.this.handleFile = handleFile;
        }

        public String getFileLocation() {
            return NettyServerStartupProperties.this.fileLocation;
        }

        public void setFileLocation(String fileLocation) {
            NettyServerStartupProperties.this.fileLocation = fileLocation;
        }

        public String getInfoLocation() {
            return NettyServerStartupProperties.this.infoLocation;
        }

        public void setInfoLocation(String infoLocation) {
            NettyServerStartupProperties.this.infoLocation = infoLocation;
        }

        public Ssl getSsl() {
            return NettyServerStartupProperties.this.ssl;
        }

        public void setSsl(Ssl ssl) {
            NettyServerStartupProperties.this.ssl = ssl;
        }

        public Gzip getGzip() {
            return NettyServerStartupProperties.this.gzip;
        }

        public void setGzip(Gzip gzip) {
            NettyServerStartupProperties.this.gzip = gzip;
        }

        public int getMaxContentLength() {
            return maxContentLength;
        }

        public void setMaxContentLength(int maxContentLength) {
            this.maxContentLength = maxContentLength;
        }

        public int getMaxInitialLineLength() {
            return maxInitialLineLength;
        }

        public void setMaxInitialLineLength(int maxInitialLineLength) {
            this.maxInitialLineLength = maxInitialLineLength;
        }

        public int getMaxHeaderSize() {
            return maxHeaderSize;
        }

        public void setMaxHeaderSize(int maxHeaderSize) {
            this.maxHeaderSize = maxHeaderSize;
        }

        public int getMaxChunkSize() {
            return maxChunkSize;
        }

        public void setMaxChunkSize(int maxChunkSize) {
            this.maxChunkSize = maxChunkSize;
        }

        public long getReadTimeoutSeconds() {
            return readTimeoutSeconds;
        }

        public void setReadTimeoutSeconds(long readTimeoutSeconds) {
            this.readTimeoutSeconds = readTimeoutSeconds;
        }

        public long getWriteTimeoutSeconds() {
            return writeTimeoutSeconds;
        }

        public void setWriteTimeoutSeconds(long writeTimeoutSeconds) {
            this.writeTimeoutSeconds = writeTimeoutSeconds;
        }

        public long getIdleTimeoutSeconds() {
            return idleTimeoutSeconds;
        }

        public void setIdleTimeoutSeconds(long idleTimeoutSeconds) {
            this.idleTimeoutSeconds = idleTimeoutSeconds;
        }
    }

    /**
     * WebSocket configuration including thread pools, connection limits, heartbeat,
     * broadcast policies, and application-level message encryption.
     */
    public static class WebSocket {
        /**
         * Enable websocket mapping and related beans.
         */
        private boolean enable = true;

        /**
         * Message sender executor core pool size.
         */
        private int corePoolSize;

        /**
         * Message sender executor max pool size.
         */
        private int maxPoolSize;

        /**
         * Message sender executor keep alive time in seconds.
         */
        private long keepAliveTime;

        /**
         * Message sender executor queue capacity. 0 means use a synchronous queue.
         */
        private int queueCapacity;

        /**
         * Broadcast behavior when a channel is not writable.
         */
        private BroadcastNonWritableChannelPolicy broadcastNonWritableChannelPolicy =
                BroadcastNonWritableChannelPolicy.SKIP;

        /**
         * Broadcast behavior when sender executor rejects a task.
         */
        private BroadcastRejectedExecutionPolicy broadcastRejectedExecutionPolicy =
                BroadcastRejectedExecutionPolicy.DROP;

        /**
         * Broadcast threading model.
         * <ul>
         *   <li>{@code EVENT_LOOP_DIRECT} (default, v1.6+): Zero-copy broadcast with
         *       EventLoop-direct delivery. Serializes the message payload once and shares
         *       it across all sessions via {@code ByteBuf.retainedDuplicate()}. Tasks are
         *       grouped by EventLoop for batch flush, eliminating the sender thread pool
         *       overhead.</li>
         *   <li>{@code THREAD_POOL_LEGACY}: v1.5.x behavior — each session's broadcast is
         *       submitted as an individual task to the sender thread pool with per-task
         *       serialization. Provided for backward compatibility.</li>
         * </ul>
         */
        private BroadcastMode broadcastMode = BroadcastMode.EVENT_LOOP_DIRECT;

        /**
         * Low water mark (in bytes) for the per-channel write buffer.
         * When the number of queued bytes drops below this threshold, the channel
         * becomes writable again. Default: 32768 (32 KB).
         */
        private int writeBufferLowWaterMark = 32768;

        /**
         * High water mark (in bytes) for the per-channel write buffer.
         * When the number of queued bytes exceeds this threshold, the channel
         * becomes non-writable and broadcasts will skip or close the session
         * per the configured policy. Default: 65536 (64 KB).
         */
        private int writeBufferHighWaterMark = 65536;

        /**
         * Flush consolidation threshold for {@code FlushConsolidationHandler}.
         * Flushes are consolidated until this many explicit flushes are observed,
         * reducing syscall overhead during high-throughput broadcast.
         * 0 or negative means disabled (no FlushConsolidationHandler is added).
         * Default: 256.
         */
        private int flushConsolidationThreshold = 256;

        /**
         * Handler executor core pool size.
         */
        private int handlerCorePoolSize;

        /**
         * Handler executor max pool size.
         */
        private int handlerMaxPoolSize;

        /**
         * Handler executor keep alive time in seconds.
         */
        private long handlerKeepAliveTime;

        /**
         * Handler executor queue capacity. 0 means use a synchronous queue.
         */
        private int handlerQueueCapacity;

        /**
         * Max handler tasks allowed to run or wait for execution.
         */
        private int handlerPermitLimit;

        /**
         * Max websocket connections. 0 or negative means unlimited.
         */
        private int maxConnections;

        /**
         * Max websocket frame payload length. 0 or negative means use the default.
         */
        private int maxFramePayloadLength;

        /**
         * Maximum buffer size in bytes for WebSocket frame aggregation. When positive,
         * a {@code WebSocketFrameAggregator} is added to the pipeline after the handshake
         * to reassemble fragmented WebSocket messages (ContinuationWebSocketFrame) into
         * complete frames before they reach the handler.
         *
         * <p>0 or negative means frame aggregation is disabled and fragmented messages
         * will be discarded with a warning.
         */
        private int maxFrameAggregationBufferSize;

        /**
         * Comma or whitespace separated allowed Origin values. Blank means allow all origins.
         */
        private String allowedOrigins;

        /**
         * Server-side heartbeat ping interval in seconds. 0 means disabled.
         */
        private long heartbeatIntervalSeconds;

        /**
         * Close the session when no inbound frame is received within this timeout. 0 means disabled.
         */
        private long heartbeatTimeoutSeconds;

        /**
         * Application-level websocket message crypto settings.
         */
        private Crypto crypto = new Crypto();

        public boolean isEnable() {
            return enable;
        }

        public void setEnable(boolean enable) {
            this.enable = enable;
        }

        public int getCorePoolSize() {
            return corePoolSize;
        }

        public void setCorePoolSize(int corePoolSize) {
            this.corePoolSize = corePoolSize;
        }

        public int getMaxPoolSize() {
            return maxPoolSize;
        }

        public void setMaxPoolSize(int maxPoolSize) {
            this.maxPoolSize = maxPoolSize;
        }

        public long getKeepAliveTime() {
            return keepAliveTime;
        }

        public void setKeepAliveTime(long keepAliveTime) {
            this.keepAliveTime = keepAliveTime;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public BroadcastNonWritableChannelPolicy getBroadcastNonWritableChannelPolicy() {
            return broadcastNonWritableChannelPolicy;
        }

        public void setBroadcastNonWritableChannelPolicy(BroadcastNonWritableChannelPolicy broadcastNonWritableChannelPolicy) {
            this.broadcastNonWritableChannelPolicy = broadcastNonWritableChannelPolicy;
        }

        public BroadcastRejectedExecutionPolicy getBroadcastRejectedExecutionPolicy() {
            return broadcastRejectedExecutionPolicy;
        }

        public void setBroadcastRejectedExecutionPolicy(BroadcastRejectedExecutionPolicy broadcastRejectedExecutionPolicy) {
            this.broadcastRejectedExecutionPolicy = broadcastRejectedExecutionPolicy;
        }

        public BroadcastMode getBroadcastMode() {
            return broadcastMode;
        }

        public void setBroadcastMode(BroadcastMode broadcastMode) {
            this.broadcastMode = broadcastMode;
        }

        public int getWriteBufferLowWaterMark() {
            return writeBufferLowWaterMark;
        }

        public void setWriteBufferLowWaterMark(int writeBufferLowWaterMark) {
            this.writeBufferLowWaterMark = writeBufferLowWaterMark;
        }

        public int getWriteBufferHighWaterMark() {
            return writeBufferHighWaterMark;
        }

        public void setWriteBufferHighWaterMark(int writeBufferHighWaterMark) {
            this.writeBufferHighWaterMark = writeBufferHighWaterMark;
        }

        public int getFlushConsolidationThreshold() {
            return flushConsolidationThreshold;
        }

        public void setFlushConsolidationThreshold(int flushConsolidationThreshold) {
            this.flushConsolidationThreshold = flushConsolidationThreshold;
        }

        public int getHandlerCorePoolSize() {
            return handlerCorePoolSize;
        }

        public void setHandlerCorePoolSize(int handlerCorePoolSize) {
            this.handlerCorePoolSize = handlerCorePoolSize;
        }

        public int getHandlerMaxPoolSize() {
            return handlerMaxPoolSize;
        }

        public void setHandlerMaxPoolSize(int handlerMaxPoolSize) {
            this.handlerMaxPoolSize = handlerMaxPoolSize;
        }

        public long getHandlerKeepAliveTime() {
            return handlerKeepAliveTime;
        }

        public void setHandlerKeepAliveTime(long handlerKeepAliveTime) {
            this.handlerKeepAliveTime = handlerKeepAliveTime;
        }

        public int getHandlerQueueCapacity() {
            return handlerQueueCapacity;
        }

        public void setHandlerQueueCapacity(int handlerQueueCapacity) {
            this.handlerQueueCapacity = handlerQueueCapacity;
        }

        public int getHandlerPermitLimit() {
            return handlerPermitLimit;
        }

        public void setHandlerPermitLimit(int handlerPermitLimit) {
            this.handlerPermitLimit = handlerPermitLimit;
        }

        public int getMaxConnections() {
            return maxConnections;
        }

        public void setMaxConnections(int maxConnections) {
            this.maxConnections = maxConnections;
        }

        public int getMaxFramePayloadLength() {
            return maxFramePayloadLength;
        }

        public void setMaxFramePayloadLength(int maxFramePayloadLength) {
            this.maxFramePayloadLength = maxFramePayloadLength;
        }

        public int getMaxFrameAggregationBufferSize() {
            return maxFrameAggregationBufferSize;
        }

        public void setMaxFrameAggregationBufferSize(int maxFrameAggregationBufferSize) {
            this.maxFrameAggregationBufferSize = maxFrameAggregationBufferSize;
        }

        public String getAllowedOrigins() {
            return allowedOrigins;
        }

        public void setAllowedOrigins(String allowedOrigins) {
            this.allowedOrigins = allowedOrigins;
        }

        public long getHeartbeatIntervalSeconds() {
            return heartbeatIntervalSeconds;
        }

        public void setHeartbeatIntervalSeconds(long heartbeatIntervalSeconds) {
            this.heartbeatIntervalSeconds = heartbeatIntervalSeconds;
        }

        public long getHeartbeatTimeoutSeconds() {
            return heartbeatTimeoutSeconds;
        }

        public void setHeartbeatTimeoutSeconds(long heartbeatTimeoutSeconds) {
            this.heartbeatTimeoutSeconds = heartbeatTimeoutSeconds;
        }

        public Crypto getCrypto() {
            return crypto;
        }

        public void setCrypto(Crypto crypto) {
            this.crypto = crypto;
        }

        /**
         * Application-level WebSocket message encryption/decryption configuration.
         * When enabled, text and/or binary frames are encrypted before sending
         * and decrypted upon receipt using the configured algorithm and key.
         */
        public static class Crypto {
            /**
             * Enable application-level message encryption/decryption. Disabled by default.
             */
            private boolean enable;

            /**
             * Algorithm identifier used by the configured MessageCryptoCodec.
             */
            private String algorithm = "CUSTOM";

            /**
             * Key identifier used by the configured MessageCryptoCodec.
             */
            private String keyId;

            /**
             * Key provider name or bean identifier used by the configured MessageCryptoCodec.
             */
            private String keyProvider;

            /**
             * Comma or whitespace separated websocket paths/URIs that should use crypto. Blank means all.
             */
            private String includeUris;

            /**
             * Comma or whitespace separated websocket paths/URIs that should not use crypto.
             */
            private String excludeUris;

            /**
             * Encrypt/decrypt text frames when crypto is enabled.
             */
            private boolean encryptText = true;

            /**
             * Encrypt/decrypt binary frames when crypto is enabled.
             */
            private boolean encryptBinary = true;

            /**
             * Close the websocket session when an encrypted frame cannot be decrypted.
             */
            private boolean closeOnDecryptFailure = true;

            /**
             * Reject plain text/binary frames when crypto is enabled for that frame type.
             */
            private boolean rejectUnencrypted = true;

            public boolean isEnable() {
                return enable;
            }

            public void setEnable(boolean enable) {
                this.enable = enable;
            }

            public String getAlgorithm() {
                return algorithm;
            }

            public void setAlgorithm(String algorithm) {
                this.algorithm = algorithm;
            }

            public String getKeyId() {
                return keyId;
            }

            public void setKeyId(String keyId) {
                this.keyId = keyId;
            }

            public String getKeyProvider() {
                return keyProvider;
            }

            public void setKeyProvider(String keyProvider) {
                this.keyProvider = keyProvider;
            }

            public String getIncludeUris() {
                return includeUris;
            }

            public void setIncludeUris(String includeUris) {
                this.includeUris = includeUris;
            }

            public String getExcludeUris() {
                return excludeUris;
            }

            public void setExcludeUris(String excludeUris) {
                this.excludeUris = excludeUris;
            }

            public boolean isEncryptText() {
                return encryptText;
            }

            public void setEncryptText(boolean encryptText) {
                this.encryptText = encryptText;
            }

            public boolean isEncryptBinary() {
                return encryptBinary;
            }

            public void setEncryptBinary(boolean encryptBinary) {
                this.encryptBinary = encryptBinary;
            }

            public boolean isCloseOnDecryptFailure() {
                return closeOnDecryptFailure;
            }

            public void setCloseOnDecryptFailure(boolean closeOnDecryptFailure) {
                this.closeOnDecryptFailure = closeOnDecryptFailure;
            }

            public boolean isRejectUnencrypted() {
                return rejectUnencrypted;
            }

            public void setRejectUnencrypted(boolean rejectUnencrypted) {
                this.rejectUnencrypted = rejectUnencrypted;
            }
        }

        /**
         * Policy for handling channels that are not writable during a broadcast.
         *
         * <ul>
         *   <li>{@code SKIP} - skip the non-writable channel and continue broadcasting</li>
         *   <li>{@code CLOSE} - close the non-writable channel</li>
         * </ul>
         */
        public enum BroadcastNonWritableChannelPolicy {
            SKIP,
            CLOSE
        }

        /**
         * Policy for handling executor rejections during a broadcast operation.
         *
         * <ul>
         *   <li>{@code DROP} - silently drop the broadcast message for the rejected channel</li>
         *   <li>{@code CALLER_RUNS} - execute the broadcast on the caller's thread</li>
         * </ul>
         */
        public enum BroadcastRejectedExecutionPolicy {
            DROP,
            CALLER_RUNS
        }

        /**
         * Broadcast threading model.
         *
         * <ul>
         *   <li>{@code EVENT_LOOP_DIRECT} — zero-copy, EventLoop-direct broadcast (v1.6 default)</li>
         *   <li>{@code THREAD_POOL_LEGACY} — per-session thread pool broadcast (v1.5.x compat)</li>
         * </ul>
         */
        public enum BroadcastMode {
            EVENT_LOOP_DIRECT,
            THREAD_POOL_LEGACY
        }
    }

}
