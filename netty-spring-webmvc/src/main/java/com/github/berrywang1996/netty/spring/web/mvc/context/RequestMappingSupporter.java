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
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.ResponseBody;
import com.github.berrywang1996.netty.spring.web.mvc.bind.annotation.RestController;
import com.github.berrywang1996.netty.spring.web.mvc.consts.HttpRequestMethod;
import com.github.berrywang1996.netty.spring.web.mvc.view.AbstractViewHandler;
import com.github.berrywang1996.netty.spring.web.mvc.view.HtmlViewHandler;
import com.github.berrywang1996.netty.spring.web.mvc.view.JsonViewHandler;
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


                    for (String url : mappingUrls) {
                        if (this.resolverMap.containsKey(url)) {

                            RequestMappingResolver resolver = this.resolverMap.get(url);

                            // if url is mapped and http request method is duplicate, throw exception.
                            if (isDuplicateMapping(annotation.method(), resolver)) {
                                throw new IllegalStateException("Ambiguous mapping uri \"" + url + "\". Cannot map " +
                                        "method " + method);
                            }

                            // if url already mapped, clone method Map and put new resolver(coverage old resolver)
                            Map<HttpRequestMethod, Method> methodMap = new HashMap<>(resolver.getMethods());
                            if (annotation.method().length == 0) {
                                // if request method not set, apply all request methods
                                methodMap.put(HttpRequestMethod.ALL, method);
                            } else {
                                for (HttpRequestMethod httpRequestMethod : annotation.method()) {
                                    methodMap.put(httpRequestMethod, method);
                                }
                            }
                            this.resolverMap.put(url, new RequestMappingResolver(url, methodMap,
                                    controllerBean.getValue(), getViewHandler(method)));

                        } else {

                            // if url not mapped, create new resolver map
                            Map<HttpRequestMethod, Method> methodMap = new HashMap<>();

                            // if annotation method not set, apply all method
                            if (annotation.method().length == 0) {
                                methodMap.put(HttpRequestMethod.ALL, method);
                            } else {
                                for (HttpRequestMethod httpRequestMethod : annotation.method()) {
                                    methodMap.put(httpRequestMethod, method);
                                }
                            }
                            this.resolverMap.put(url, new RequestMappingResolver(url, methodMap,
                                    controllerBean.getValue(), getViewHandler(method)));

                        }
                    }
                }
            }
        }

        return resolverMap;
    }

    private AbstractViewHandler getViewHandler(Method method) {

        if (method.isAnnotationPresent(ResponseBody.class) ||
                method.getDeclaringClass().isAnnotationPresent(ResponseBody.class) ||
                method.getDeclaringClass().isAnnotationPresent(RestController.class)) {
            // if method or class contains annotation ResponseBody and RestController return value is json
            return new JsonViewHandler();
        } else {
            // else return value is html
            return new HtmlViewHandler();
        }

    }

    private boolean isDuplicateMapping(HttpRequestMethod[] methods, RequestMappingResolver resolver) {
        boolean isAmbiguous = false;
        if (methods.length == 0 || resolver.getMethodKey().size() == 0) {
            // if request method not set, apply all request methods, so it is ambiguous
            isAmbiguous = true;
        } else {
            Set<HttpRequestMethod> methodKey = resolver.getMethodKey();
            for (HttpRequestMethod httpRequestMethod : methods) {
                if (methodKey.contains(httpRequestMethod)) {
                    isAmbiguous = true;
                    break;
                }
            }
        }
        return isAmbiguous;
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
                    urls.add(clzUrl + methodUrl);
                }
            } else {
                urls.add(methodUrl);
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

}
