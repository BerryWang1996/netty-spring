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

package com.github.berrywang1996.netty.spring.web.mvc.context;

import com.github.berrywang1996.netty.spring.web.context.MappingSupporter;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RequestMapping;
import com.github.berrywang1996.netty.spring.web.startup.NettyServerStartupProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.stereotype.Controller;

import java.lang.reflect.Method;
import java.util.*;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
@Slf4j
public class RequestMappingSupporter implements MappingSupporter<RequestMappingResolver> {

    private NettyServerStartupProperties startupProperties;

    private Map<String, RequestMappingResolver> resolverMap = new HashMap<>();

    @Override
    public Map<String, RequestMappingResolver> initMappingResolverMap(NettyServerStartupProperties startupProperties,
                                                                      ApplicationContext applicationContext) {

        this.startupProperties = startupProperties;

        Map<String, Object> beans = applicationContext.getBeansWithAnnotation(Controller.class);
        for (Map.Entry<String, Object> controllerBean : beans.entrySet()) {
            /*
               find method had annotation in
                   DeleteMapping
                   GetMapping
                   PostMapping
                   PutMapping
                   RequestMapping
             */
            Method[] methods = controllerBean.getValue().getClass().getMethods();
            for (Method method : methods) {

                RequestMapping annotation = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
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
                    log.info("Mapping {{}{}} onto {}",
                            mappingUrls,
                            annotation.method().length > 0 ? ",method=" + Arrays.toString(annotation.method()) : "",
                            method);

                    RequestMappingResolver resolver =
                            new RequestMappingResolver(method, controllerBean, Arrays.asList(annotation.method()));

                    for (String url : mappingUrls) {
                        if (this.resolverMap.containsKey(url)) {
                            throw new IllegalStateException("Ambiguous mapping uri \"" + url + "\". Cannot map method" +
                                    " " + method);
                        }
                        this.resolverMap.put(url, resolver);
                    }
                }
            }
        }

        return resolverMap;
    }

    private List<String> getMappingUrls(Method method) {

        List<String> urls = new ArrayList<>();

        RequestMapping methodAnno =
                AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
        String[] methodUrls = getAnnotationUrls(methodAnno);

        RequestMapping clzAnno =
                AnnotatedElementUtils.findMergedAnnotation(method.getDeclaringClass(), RequestMapping.class);
        String[] clzUrls = getAnnotationUrls(clzAnno);

        for (String methodUrl : methodUrls) {
            if (clzUrls.length > 0) {
                for (String clzUrl : clzUrls) {
                    urls.add(fixUrl(fixUrl(clzUrl) + fixUrl(methodUrl)));
                }
            } else {
                urls.add(fixUrl(methodUrl));
            }
        }
        return urls;
    }

    private static String[] getAnnotationUrls(RequestMapping methodAnno) {
        if (methodAnno == null) {
            return new String[0];
        }
        return methodAnno.value();
    }

    private static String fixUrl(String url) {
        return "/" + cleanUrl(url);
    }

    private static String cleanUrl(String url) {
        if (url.endsWith("/")) {
            url = url.substring(0, url.lastIndexOf("/"));
            url = cleanUrl(url);
        }
        if (url.startsWith("/")) {
            url = url.substring(url.indexOf("/") + 1);
            url = cleanUrl(url);
        }
        return url;
    }

}
