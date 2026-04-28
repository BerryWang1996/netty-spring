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

import io.netty.channel.ChannelHandlerContext;
import org.springframework.context.ApplicationContext;
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.util.PathMatcher;

import java.lang.reflect.Method;
import java.util.*;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
public abstract class AbstractMappingResolver<T, K> {

    private final String url;

    private final Map<K, Method> methods;

    private final Map<K, Map<String, Class>> methodParamTypes;

    private final Object invokeRef;

    private final ApplicationContext applicationContext;

    private final String invokeBeanName;

    private PathMatcher pathMatcher;

    private HttpRuntimeRecorder httpRuntimeRecorder = HttpRuntimeRecorder.noop();

    public AbstractMappingResolver(String url, Map<K, Method> methods, Object invokeRef) {
        this(url, methods, invokeRef, null, null);
    }

    public AbstractMappingResolver(String url,
                                   Map<K, Method> methods,
                                   ApplicationContext applicationContext,
                                   String invokeBeanName) {
        this(url, methods, null, applicationContext, invokeBeanName);
    }

    private AbstractMappingResolver(String url,
                                    Map<K, Method> methods,
                                    Object invokeRef,
                                    ApplicationContext applicationContext,
                                    String invokeBeanName) {
        this.url = url;
        this.methods = Collections.unmodifiableMap(methods);
        this.invokeRef = invokeRef;
        this.applicationContext = applicationContext;
        this.invokeBeanName = invokeBeanName;

        // parse method parameters
        this.methodParamTypes = parseMethodParameters();
    }

    public Map<K, Method> getMethods() {
        return methods;
    }

    public Method getMethod(K key) {
        return methods.get(key);
    }

    public Map<String, Class> getMethodParamType(K key) {
        return methodParamTypes.get(key);
    }

    public Set<K> getMethodKey() {
        if (methods == null) {
            return null;
        }
        return methods.keySet();
    }

    public String getUrl() {
        return url;
    }

    public Object getInvokeRef() {
        if (this.invokeBeanName != null && this.applicationContext != null) {
            return this.applicationContext.getBean(this.invokeBeanName);
        }
        return invokeRef;
    }

    public PathMatcher getPathMatcher() {
        return pathMatcher;
    }

    public void setPathMatcher(PathMatcher pathMatcher) {
        this.pathMatcher = pathMatcher;
    }

    public void setHttpRuntimeRecorder(HttpRuntimeRecorder httpRuntimeRecorder) {
        this.httpRuntimeRecorder = httpRuntimeRecorder == null
                ? HttpRuntimeRecorder.noop()
                : httpRuntimeRecorder;
    }

    protected HttpRuntimeRecorder getHttpRuntimeRecorder() {
        return httpRuntimeRecorder;
    }

    public abstract void resolve(ChannelHandlerContext ctx, T msg) throws Exception;

    public void resolveException(ChannelHandlerContext ctx, Exception e) throws Exception {
        throw e;
    }

    public void onChannelInactive(ChannelHandlerContext ctx) throws Exception {
    }

    public void shutdown() throws Exception {
    }

    public int getActiveSessionCount() {
        return 0;
    }

    public Map<K, Map<String, Class>> parseMethodParameters() {
        Map<K, Map<String, Class>> tempMethodParamTypes = new HashMap<>();
        for (Map.Entry<K, Method> kMethodEntry : methods.entrySet()) {
            LinkedHashMap<String, Class> methodParams = new LinkedHashMap<>();
            LocalVariableTableParameterNameDiscoverer u = new LocalVariableTableParameterNameDiscoverer();
            String[] params = u.getParameterNames(kMethodEntry.getValue());
            Class<?>[] parameterTypes = kMethodEntry.getValue().getParameterTypes();
            if (params != null && parameterTypes.length == params.length) {
                for (int i = 0; i < params.length; i++) {
                    methodParams.put(params[i], parameterTypes[i]);
                }
            }
            tempMethodParamTypes.put(kMethodEntry.getKey(), methodParams);
        }
        return Collections.unmodifiableMap(tempMethodParamTypes);
    }

    public abstract void removeSession(String sessionId);

}
