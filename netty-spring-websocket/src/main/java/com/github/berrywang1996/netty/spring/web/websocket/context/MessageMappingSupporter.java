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
import com.github.berrywang1996.netty.spring.web.websocket.bind.MessageMappingResolver;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import com.github.berrywang1996.netty.spring.web.websocket.crypto.MessageCryptoCodec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.Semaphore;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class MessageMappingSupporter implements MappingSupporter<MessageMappingResolver> {

    private NettyServerStartupProperties startupProperties;

    private ApplicationContext applicationContext;

    private final Map<String, MessageMappingResolver> resolverMap = new HashMap<>();

    private Semaphore connectionSemaphore;

    private MessageCryptoCodec messageCryptoCodec;

    @Override
    public Map<String, MessageMappingResolver> initMappingResolverMap(NettyServerStartupProperties startupProperties,
                                                                      ApplicationContext applicationContext) {

        this.startupProperties = startupProperties;
        this.applicationContext = applicationContext;
        this.connectionSemaphore = initConnectionSemaphore(startupProperties);
        this.messageCryptoCodec = initMessageCryptoCodec(startupProperties, applicationContext);

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

        return resolverMap;
    }

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

    private static String[] getAnnotationUrls(MessageMapping methodAnno) {
        if (methodAnno == null) {
            return new String[0];
        }
        return methodAnno.value();
    }

    private MessageMappingResolver newResolver(String url, Map<MessageType, Method> methods, String invokeBeanName) {
        return new MessageMappingResolver(
                url,
                methods,
                this.applicationContext,
                invokeBeanName,
                this.startupProperties == null ? null : this.startupProperties.getWebSocket(),
                this.connectionSemaphore,
                this.messageCryptoCodec);
    }

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

    private MessageCryptoCodec initMessageCryptoCodec(NettyServerStartupProperties startupProperties,
                                                      ApplicationContext applicationContext) {
        if (!isCryptoEnabled(startupProperties)) {
            return null;
        }
        Map<String, MessageCryptoCodec> codecs = applicationContext.getBeansOfType(MessageCryptoCodec.class);
        if (codecs.isEmpty()) {
            throw new IllegalStateException(
                    "Websocket crypto is enabled but no MessageCryptoCodec bean is available.");
        }
        if (codecs.size() > 1) {
            throw new IllegalStateException(
                    "Websocket crypto requires exactly one MessageCryptoCodec bean, but found " + codecs.size() + ".");
        }
        return codecs.values().iterator().next();
    }

    private boolean isCryptoEnabled(NettyServerStartupProperties startupProperties) {
        if (startupProperties == null
                || startupProperties.getWebSocket() == null
                || startupProperties.getWebSocket().getCrypto() == null) {
            return false;
        }
        return startupProperties.getWebSocket().getCrypto().isEnable();
    }

}
