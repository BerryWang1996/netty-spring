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

package com.github.berrywang1996.netty.spring.web.websocket.context;

import com.github.berrywang1996.netty.spring.web.context.MappingSupporter;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import com.github.berrywang1996.netty.spring.web.util.StringUtil;
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.AesGcmMessageCryptoCodec;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoCodec;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoKeyProvider;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoPolicy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * Spring-aware supporter that scans the application context for
 * {@code @MessageMapping}-annotated methods and builds a map of
 * {@link MessageMappingResolver} instances.
 *
 * <p>During {@link #initMappingResolverMap}, this class:
 * <ol>
 *   <li>Discovers all Spring {@code @Component} beans</li>
 *   <li>Inspects public methods for {@code @MessageMapping} annotations</li>
 *   <li>Builds compound URLs from class-level and method-level path prefixes</li>
 *   <li>Creates a {@link MessageMappingResolver} per unique URI, aggregating
 *       multiple {@link MessageType} handlers on the same URI</li>
 *   <li>Initializes optional infrastructure: connection semaphore, crypto codec/policy,
 *       event recorder, and handshake interceptor</li>
 * </ol>
 *
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class MessageMappingSupporter implements MappingSupporter<MessageMappingResolver> {

    /** Server startup configuration properties. */
    private NettyServerStartupProperties startupProperties;

    /** Spring application context used for bean discovery. */
    private ApplicationContext applicationContext;

    /** Accumulated resolver map, keyed by WebSocket URI path. */
    private final Map<String, MessageMappingResolver> resolverMap = new HashMap<>();

    /** Optional semaphore that enforces the global WebSocket connection limit. */
    private Semaphore connectionSemaphore;

    /** Optional codec for application-level frame encryption/decryption. */
    private MessageCryptoCodec messageCryptoCodec;

    /** Optional per-session policy determining whether crypto is applied. */
    private MessageCryptoPolicy messageCryptoPolicy;

    /** Shared event recorder that collects metrics across all WebSocket resolvers. */
    private WebSocketEventRecorder eventRecorder;

    /** Optional interceptor for custom handshake authentication/authorization. */
    private WebSocketHandshakeInterceptor handshakeInterceptor;

    /**
     * Scans the Spring context for {@code @MessageMapping} methods and builds
     * the URI-to-resolver mapping.
     *
     * @param startupProperties  server startup configuration containing WebSocket settings
     * @param applicationContext the Spring application context
     * @return an unmodifiable map of WebSocket URI paths to their resolvers
     */
    @Override
    public Map<String, MessageMappingResolver> initMappingResolverMap(NettyServerStartupProperties startupProperties,
                                                                      ApplicationContext applicationContext) {

        this.startupProperties = startupProperties;
        this.applicationContext = applicationContext;
        this.connectionSemaphore = initConnectionSemaphore(startupProperties);
        this.messageCryptoCodec = initMessageCryptoCodec(startupProperties, applicationContext);
        this.messageCryptoPolicy = initMessageCryptoPolicy(startupProperties, applicationContext);
        this.eventRecorder = new WebSocketEventRecorder();
        this.handshakeInterceptor = initHandshakeInterceptor(applicationContext);

        String[] beanNames = applicationContext.getBeanNamesForAnnotation(Component.class);
        log.debug("Find method had annotation \"MessageMapping\"");
        for (String beanName : beanNames) {
            Class<?> beanType = applicationContext.getType(beanName);
            if (beanType == null) {
                log.debug("Skip bean {} because bean type is not resolvable yet.", beanName);
                continue;
            }
            /*
               find method had annotation MessageMapping
             */
            Method[] methods = beanType.getMethods();
            for (Method method : methods) {
                MessageMapping annotation = AnnotatedElementUtils.findMergedAnnotation(method, MessageMapping.class);

                if (annotation != null) {

                    log.debug("Found annotation {} at method {}", annotation, method);

                    // check port
                    if (annotation.port().length != 0) {
                        boolean notMap = true;
                        int[] ports = annotation.port();
                        for (int port : ports) {
                            if (port == this.startupProperties.getPort()) {
                                notMap = false;
                                break;
                            }
                        }
                        if (notMap) {
                            log.debug("The method marked ports did not contained port {}",
                                    this.startupProperties.getPort());
                            continue;
                        }
                    }

                    // get mapping url
                    List<String> mappingUrls = getMappingUrls(method);

                    // map
                    log.info("Mapping message {{},messageType={}} onto {}",
                            mappingUrls,
                            annotation.messageType(),
                            method);

                    for (String url : mappingUrls) {
                        if (this.resolverMap.containsKey(url)) {

                            // if url is mapped and message type is duplicate, throw exception.
                            if (this.resolverMap.get(url).getMethodKey().contains(annotation.messageType())) {
                                throw new IllegalStateException("Ambiguous message uri \"" + url + "\". Cannot map " +
                                        "method " + method);
                            }

                            // put new message type but same url
                            Map<MessageType, Method> methodMap = new HashMap<>(this.resolverMap.get(url).getMethods());
                            methodMap.put(annotation.messageType(), method);
                            this.resolverMap.put(url, newResolver(url, methodMap, beanName));

                        } else {

                            // if url is not mapped, continue
                            this.resolverMap.put(url, newResolver(
                                    url,
                                    Collections.singletonMap(annotation.messageType(), method),
                                    beanName));

                        }
                    }
                }
            }
        }

        configureEventRecorderAndInterceptor(resolverMap);
        return resolverMap;
    }

    /**
     * Propagates the shared event recorder and optional handshake interceptor to all resolvers.
     */
    private void configureEventRecorderAndInterceptor(Map<String, MessageMappingResolver> resolverMap) {
        for (MessageMappingResolver resolver : resolverMap.values()) {
            resolver.setEventRecorder(this.eventRecorder);
            if (this.handshakeInterceptor != null) {
                resolver.setHandshakeInterceptor(this.handshakeInterceptor);
            }
        }
    }

    /**
     * Discovers at most one {@link WebSocketHandshakeInterceptor} bean from the context.
     *
     * @param applicationContext the Spring application context
     * @return the interceptor bean, or {@code null} if none is defined
     * @throws IllegalStateException if more than one interceptor bean is found
     */
    private WebSocketHandshakeInterceptor initHandshakeInterceptor(ApplicationContext applicationContext) {
        Map<String, WebSocketHandshakeInterceptor> interceptors =
                applicationContext.getBeansOfType(WebSocketHandshakeInterceptor.class);
        if (interceptors.isEmpty()) {
            return null;
        }
        if (interceptors.size() > 1) {
            throw new IllegalStateException(
                    "Expected at most one WebSocketHandshakeInterceptor bean, but found "
                            + interceptors.size() + ": " + interceptors.keySet()
                            + ". Action: keep one interceptor bean, or compose them into a single bean.");
        }
        WebSocketHandshakeInterceptor interceptor = interceptors.values().iterator().next();
        log.info("Registered WebSocket handshake interceptor: {}", interceptor.getClass().getName());
        return interceptor;
    }

    /**
     * Returns the shared event recorder used across all resolvers.
     *
     * @return the WebSocket event recorder
     */
    public WebSocketEventRecorder getEventRecorder() {
        return eventRecorder;
    }

    /**
     * Resolves the effective mapping URLs for a handler method by combining class-level
     * and method-level {@code @MessageMapping} path prefixes.
     *
     * @param method the annotated handler method
     * @return the list of fully-qualified WebSocket URI paths
     */
    private List<String> getMappingUrls(Method method) {

        List<String> urls = new ArrayList<>();

        MessageMapping methodAnno =
                AnnotatedElementUtils.findMergedAnnotation(method, MessageMapping.class);
        String[] methodUrls = getAnnotationUrls(methodAnno);

        MessageMapping clzAnno =
                AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), MessageMapping.class);
        String[] clzUrls = getAnnotationUrls(clzAnno);

        for (String methodUrl : methodUrls) {
            if (clzUrls.length > 0) {
                for (String clzUrl : clzUrls) {
                    urls.add(clzUrl + methodUrl);
                }
            } else {
                urls.add(methodUrl);
            }
        }
        return urls;
    }

    /**
     * Extracts the URL paths from a {@code @MessageMapping} annotation.
     *
     * @param methodAnno the annotation, or {@code null}
     * @return the URL array from the annotation, or an empty array if the annotation is {@code null}
     */
    private static String[] getAnnotationUrls(MessageMapping methodAnno) {
        if (methodAnno == null) {
            return new String[0];
        }
        return methodAnno.value();
    }

    /**
     * Factory method that creates a new resolver with all current configuration.
     *
     * @param url            the WebSocket mapping URI
     * @param methods        the message-type-to-method mapping
     * @param invokeBeanName the Spring bean name of the handler
     * @return a fully configured resolver
     */
    private MessageMappingResolver newResolver(String url, Map<MessageType, Method> methods, String invokeBeanName) {
        return new MessageMappingResolver(
                url,
                methods,
                this.applicationContext,
                invokeBeanName,
                this.startupProperties == null ? null : this.startupProperties.getWebSocket(),
                this.connectionSemaphore,
                this.messageCryptoCodec,
                this.messageCryptoPolicy);
    }

    /**
     * Initializes the connection semaphore based on the configured max connections.
     *
     * @param startupProperties server startup properties
     * @return a semaphore with the configured permit count, or {@code null} if no limit is set
     */
    private Semaphore initConnectionSemaphore(NettyServerStartupProperties startupProperties) {
        if (startupProperties == null || startupProperties.getWebSocket() == null) {
            return null;
        }
        int maxConnections = startupProperties.getWebSocket().getMaxConnections();
        if (maxConnections <= 0) {
            return null;
        }
        return new Semaphore(maxConnections);
    }

    /**
     * Initializes the message crypto codec. If crypto is enabled and the algorithm is
     * {@code AES-GCM}, a built-in {@link AesGcmMessageCryptoCodec} is created; otherwise
     * a custom bean must be provided.
     *
     * @param startupProperties  server startup properties containing crypto configuration
     * @param applicationContext the Spring application context for bean lookup
     * @return the crypto codec, or {@code null} if crypto is disabled
     * @throws IllegalStateException if crypto is enabled but no suitable codec is available
     */
    private MessageCryptoCodec initMessageCryptoCodec(NettyServerStartupProperties startupProperties,
                                                      ApplicationContext applicationContext) {
        if (!isCryptoEnabled(startupProperties)) {
            return null;
        }
        Map<String, MessageCryptoCodec> codecs = applicationContext.getBeansOfType(MessageCryptoCodec.class);
        if (codecs.size() > 1) {
            throw new IllegalStateException(
                    "Websocket crypto requires exactly one MessageCryptoCodec bean, but found " + codecs.size()
                            + ": " + codecs.keySet() + ". Action: keep one codec bean, or set "
                            + "server.netty.websocket.crypto.algorithm=AES-GCM and remove custom codec beans.");
        }
        if (codecs.size() == 1) {
            return codecs.values().iterator().next();
        }
        NettyServerStartupProperties.WebSocket.Crypto cryptoProperties = startupProperties.getWebSocket().getCrypto();
        if (AesGcmMessageCryptoCodec.ALGORITHM.equalsIgnoreCase(cryptoProperties.getAlgorithm())) {
            return new AesGcmMessageCryptoCodec(
                    cryptoProperties,
                    resolveMessageCryptoKeyProvider(applicationContext, cryptoProperties));
        }
        throw new IllegalStateException(
                "Websocket crypto is enabled but no MessageCryptoCodec bean is available for algorithm "
                        + cryptoProperties.getAlgorithm() + ". Action: define a MessageCryptoCodec bean for CUSTOM "
                        + "or set server.netty.websocket.crypto.algorithm=AES-GCM.");
    }

    /**
     * Initializes the optional per-session crypto policy bean.
     *
     * @param startupProperties  server startup properties
     * @param applicationContext the Spring application context for bean lookup
     * @return the crypto policy, or {@code null} if none is defined or crypto is disabled
     * @throws IllegalStateException if more than one policy bean is found
     */
    private MessageCryptoPolicy initMessageCryptoPolicy(NettyServerStartupProperties startupProperties,
                                                        ApplicationContext applicationContext) {
        if (!isCryptoEnabled(startupProperties)) {
            return null;
        }
        Map<String, MessageCryptoPolicy> policies = applicationContext.getBeansOfType(MessageCryptoPolicy.class);
        if (policies.size() > 1) {
            throw new IllegalStateException(
                    "Websocket crypto requires at most one MessageCryptoPolicy bean, but found "
                            + policies.size() + ": " + policies.keySet()
                            + ". Action: keep one policy bean, or merge policy rules into a composite policy.");
        }
        if (policies.size() == 1) {
            return policies.values().iterator().next();
        }
        return null;
    }

    /**
     * Resolves the {@link MessageCryptoKeyProvider} bean for AES-GCM crypto.
     * Supports explicit provider name via configuration or auto-detection when
     * exactly one provider bean exists.
     *
     * @param applicationContext the Spring application context
     * @param cryptoProperties   crypto configuration containing the optional key-provider name
     * @return the resolved key provider
     * @throws IllegalStateException if no provider is found or multiple providers exist without explicit selection
     */
    private MessageCryptoKeyProvider resolveMessageCryptoKeyProvider(
            ApplicationContext applicationContext,
            NettyServerStartupProperties.WebSocket.Crypto cryptoProperties) {
        Map<String, MessageCryptoKeyProvider> providers =
                applicationContext.getBeansOfType(MessageCryptoKeyProvider.class);
        String providerName = cryptoProperties.getKeyProvider();
        if (!StringUtil.isBlank(providerName)) {
            MessageCryptoKeyProvider provider = providers.get(providerName);
            if (provider == null) {
                throw new IllegalStateException(
                        "Websocket crypto key provider bean not found: " + providerName
                                + ". Available provider bean(s): " + providers.keySet()
                                + ". Action: check server.netty.websocket.crypto.key-provider or rename the bean.");
            }
            return provider;
        }
        if (providers.size() == 1) {
            return providers.values().iterator().next();
        }
        if (providers.isEmpty()) {
            throw new IllegalStateException(
                    "AES-GCM websocket crypto requires a MessageCryptoKeyProvider bean. Action: define a bean that "
                            + "resolves server.netty.websocket.crypto.key-id, or disable crypto for this profile.");
        }
        throw new IllegalStateException(
                "AES-GCM websocket crypto requires exactly one MessageCryptoKeyProvider bean or crypto.key-provider, "
                        + "but found " + providers.keySet()
                        + ". Action: set server.netty.websocket.crypto.key-provider to the intended bean name.");
    }

    /**
     * Checks whether WebSocket crypto is enabled in the startup configuration.
     *
     * @param startupProperties the server startup properties
     * @return {@code true} if crypto is enabled
     */
    private boolean isCryptoEnabled(NettyServerStartupProperties startupProperties) {
        if (startupProperties == null
                || startupProperties.getWebSocket() == null
                || startupProperties.getWebSocket().getCrypto() == null) {
            return false;
        }
        return startupProperties.getWebSocket().getCrypto().isEnable();
    }

}
