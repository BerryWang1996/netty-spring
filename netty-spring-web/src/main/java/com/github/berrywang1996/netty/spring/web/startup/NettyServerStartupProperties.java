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

import java.io.File;
import java.io.IOException;

/**
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
    }

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

}
