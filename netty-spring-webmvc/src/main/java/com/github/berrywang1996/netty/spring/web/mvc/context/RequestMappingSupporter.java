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
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

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

                    // map
                    log.info("Mapping {{}{}} onto {}",
                            Arrays.toString(annotation.value()),
                            annotation.method().length > 0 ? ",method=" + Arrays.toString(annotation.method()) : "",
                            method);

                    RequestMappingResolver resolver =
                            new RequestMappingResolver(method, controllerBean, Arrays.asList(annotation.method()));

                    for (String url : annotation.value()) {
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

}
