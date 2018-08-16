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
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.AutowiredMessageSender;
import com.github.berrywang1996.netty.spring.web.websocket.bind.annotation.MessageMapping;
import com.github.berrywang1996.netty.spring.web.websocket.consts.MessageType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class MessageMappingSupporter implements MappingSupporter<MessageMappingResolver> {

    private NettyServerStartupProperties startupProperties;

    private ApplicationContext applicationContext;

    private Map<String, MessageMappingResolver> resolverMap = new HashMap<>();

    @Override
    public Map<String, MessageMappingResolver> initMappingResolverMap(NettyServerStartupProperties startupProperties,
                                                                      ApplicationContext applicationContext) {

        this.startupProperties = startupProperties;
        this.applicationContext = applicationContext;

        // inject MessageSender into spring beans
        injectMessageSender();

        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Component.class);
        for (Map.Entry<String, Object> controllerBean : beans.entrySet()) {
            /*
               find method had annotation MessageMapping
             */
            Method[] methods = controllerBean.getValue().getClass().getMethods();
            for (Method method : methods) {
                MessageMapping annotation = AnnotatedElementUtils.findMergedAnnotation(method, MessageMapping.class);

                if (annotation != null) {

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
                            this.resolverMap.put(url, new MessageMappingResolver(url, methodMap,
                                    controllerBean.getValue()));

                        } else {

                            // if url is not mapped, continue
                            this.resolverMap.put(url, new MessageMappingResolver(url, Collections.singletonMap(
                                    annotation.messageType(), method), controllerBean.getValue()));

                        }
                    }
                }
            }
        }

        return resolverMap;
    }

    private void injectMessageSender() {

        MessageSender messageSender = new DefaultMessageSender(resolverMap);

        Map<String, Object> beans = this.applicationContext.getBeansWithAnnotation(Component.class);
        for (Map.Entry<String, Object> objectEntry : beans.entrySet()) {
            Field[] declaredFields = objectEntry.getValue().getClass().getDeclaredFields();
            for (Field field : declaredFields) {
                Class<?> type = field.getType();
                if (type.isAssignableFrom(MessageSender.class) && field.getAnnotation(AutowiredMessageSender.class) != null) {
                    field.setAccessible(true);
                    try {
                        log.debug("Autowired field message sender into object named {}, class {}",
                                objectEntry.getKey(), objectEntry.getValue().getClass());
                        field.set(objectEntry.getValue(), messageSender);
                    } catch (IllegalAccessException e) {
                        log.debug("Autowired message sender error {}", e);
                    }
                    break;
                }
            }
        }

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

}
