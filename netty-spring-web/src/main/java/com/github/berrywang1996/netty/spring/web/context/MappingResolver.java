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
import org.springframework.core.LocalVariableTableParameterNameDiscoverer;
import org.springframework.util.PathMatcher;

import java.lang.reflect.Method;
import java.util.*;

/**
 * @author berrywang1996
 * @since V1.0.0
 */
public abstract class MappingResolver<T, K> {

    private final Map<K, Method> methods;

    private final Map<K, Map<String, Class>> methodParamTypes;

    private final Object invokeRef;

    private PathMatcher pathMatcher;

    public MappingResolver(Map<K, Method> methods, Object invokeRef) {

        this.methods = Collections.unmodifiableMap(methods);
        this.invokeRef = invokeRef;

        // parse method parameters
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
        this.methodParamTypes = Collections.unmodifiableMap(tempMethodParamTypes);
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

    public Object getInvokeRef() {
        return invokeRef;
    }

    public PathMatcher getPathMatcher() {
        return pathMatcher;
    }

    public void setPathMatcher(PathMatcher pathMatcher) {
        this.pathMatcher = pathMatcher;
    }

    public abstract void resolve(ChannelHandlerContext ctx, T msg);

}
